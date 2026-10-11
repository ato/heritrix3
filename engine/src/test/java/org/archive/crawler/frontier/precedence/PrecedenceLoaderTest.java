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

package org.archive.crawler.frontier.precedence;

import static org.archive.modules.CoreAttributeConstants.A_PRECALC_PRECEDENCE;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.archive.bdb.StoredClassCatalog;
import org.archive.bdb.StoredSortedMap;
import org.archive.modules.recrawl.PersistProcessor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.moaduda.onggi.Store;
import com.sleepycat.bind.serial.SerialBinding;
import com.sleepycat.bind.tuple.StringBinding;

public class PrecedenceLoaderTest {
    @TempDir
    Path dir;

    @SuppressWarnings({"rawtypes"})
    @Test
    public void loadsPrecedencesIntoAStore() throws Exception {
        File source = dir.resolve("precedences.txt").toFile();
        Files.writeString(source.toPath(), "http://example.com/ 3\nexample.org/a 7\n");
        File target = dir.resolve("history").toFile();
        PrecedenceLoader.main(new String[] {source.getPath(), target.getPath()});
        // a second run merges into the same store
        Files.writeString(source.toPath(), "http://example.com/ 4\n");
        PrecedenceLoader.main(new String[] {source.getPath(), target.getPath()});

        try (Store store = PersistProcessor.setupCopyEnvironment(target, true)) {
            StoredSortedMap<String,Map> history = new StoredSortedMap<String,Map>(
                    store.keyspace(PersistProcessor.URI_HISTORY_DBNAME), new StringBinding(),
                    new SerialBinding<Map>(new StoredClassCatalog(store), Map.class));
            assertEquals(2, history.size());
            assertEquals(4, history.get(PersistProcessor.persistKeyFor("http://example.com/"))
                    .get(A_PRECALC_PRECEDENCE));
            assertEquals(7, history.get(PersistProcessor.persistKeyFor("http://example.org/a"))
                    .get(A_PRECALC_PRECEDENCE));
        }
    }
}
