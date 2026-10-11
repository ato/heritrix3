/*
 *  This file is part of the Heritrix web crawler (crawler.archive.org).
 *
 *  Licensed to the Internet Archive (IA) by one or more individual
 *  contributors.
 *
 *  The IA licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.archive.modules.recrawl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.lang3.SerializationUtils;
import org.archive.bdb.BdbModule;
import org.archive.bdb.StoredClassCatalog;
import org.archive.bdb.StoredSortedMap;
import org.archive.spring.ConfigFile;
import org.archive.spring.ConfigPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.moaduda.onggi.Store;
import com.sleepycat.bind.serial.SerialBinding;
import com.sleepycat.bind.tuple.StringBinding;

/**
 * Recrawl history carried between stores: a persist log or a store copied
 * by {@link PersistProcessor#main}, and loaded by {@code preloadSource}.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
public class PersistProcessorTest {
    @TempDir
    Path dir;

    private static Map<String, Map> history() {
        Map<String, Map> history = new TreeMap<String, Map>();
        for (String uri : List.of("http://example.com/", "http://example.com/a")) {
            HashMap<String, Object> fetch = new HashMap<String, Object>();
            fetch.put("content-digest", "sha1:" + uri.length());
            fetch.put("etag", "\"" + uri.hashCode() + "\"");
            history.put(PersistProcessor.persistKeyFor(uri), fetch);
        }
        return history;
    }

    private File persistLog() throws IOException {
        StringBuilder log = new StringBuilder();
        for (Map.Entry<String, Map> e : history().entrySet()) {
            log.append(e.getKey()).append(' ').append(new String(
                    Base64.encodeBase64(SerializationUtils.serialize((Serializable) e.getValue())),
                    StandardCharsets.UTF_8)).append('\n');
        }
        File file = dir.resolve("persistlog.txtser").toFile();
        Files.writeString(file.toPath(), log);
        return file;
    }

    private static Map<String, Map> read(File storeDir) throws IOException {
        try (Store store = PersistProcessor.setupCopyEnvironment(storeDir, true)) {
            StoredSortedMap<String, Map> map = new StoredSortedMap<String, Map>(
                    store.keyspace(PersistProcessor.URI_HISTORY_DBNAME), new StringBinding(),
                    new SerialBinding<Map>(new StoredClassCatalog(store), Map.class));
            return new TreeMap<String, Map>(map);
        }
    }

    @Test
    public void mainCopiesLogsAndStores() throws IOException {
        File fromLog = dir.resolve("fromLog").toFile();
        File copy = dir.resolve("copy").toFile();
        PersistProcessor.main(new String[] {persistLog().getPath(), fromLog.getPath()});
        assertEquals(history(), read(fromLog));
        PersistProcessor.main(new String[] {fromLog.getPath(), copy.getPath()});
        assertEquals(history(), read(copy));
    }

    @Test
    public void preloadSourceLoadsAStore() throws Exception {
        File previous = dir.resolve("previous").toFile();
        assertEquals(2, PersistProcessor.populatePersistEnv(persistLog().getPath(), previous));

        BdbModule bdb = new BdbModule();
        bdb.setDir(new ConfigPath("state", dir.resolve("state").toString()));
        bdb.start();
        PersistLoadProcessor loader = new PersistLoadProcessor();
        loader.setBdbModule(bdb);
        loader.setPreloadSource(new ConfigFile("preload source", previous.getPath()));
        loader.start();
        assertEquals(history(), new TreeMap<String, Map>(loader.store));
        loader.stop();
        bdb.close();
    }

    @Test
    public void refusesJeEnvironments() throws IOException {
        File je = dir.resolve("je").toFile();
        je.mkdirs();
        Files.write(new File(je, "00000000.jdb").toPath(), new byte[] {1, 2, 3});
        File target = dir.resolve("target").toFile();
        assertThrows(IllegalStateException.class,
                () -> PersistProcessor.populatePersistEnv(je.getPath(), target));
        assertThrows(IllegalStateException.class,
                () -> PersistProcessor.populatePersistEnv(persistLog().getPath(), je));
        assertEquals(List.of("00000000.jdb"), List.of(je.list()));

        BdbModule bdb = new BdbModule();
        bdb.setDir(new ConfigPath("state", dir.resolve("state").toString()));
        bdb.start();
        PersistLoadProcessor loader = new PersistLoadProcessor();
        loader.setBdbModule(bdb);
        loader.setPreloadSource(new ConfigFile("preload source", je.getPath()));
        IllegalStateException e = assertThrows(IllegalStateException.class, loader::start);
        assertTrue(e.getMessage().contains("BDB JE"), e.getMessage());
        bdb.close();
    }
}
