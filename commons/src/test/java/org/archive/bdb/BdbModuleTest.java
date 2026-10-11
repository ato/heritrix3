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

package org.archive.bdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.archive.checkpointing.Checkpoint;
import org.archive.spring.ConfigPath;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.GenericXmlApplicationContext;
import org.springframework.core.io.ByteArrayResource;

public class BdbModuleTest {
    @TempDir
    Path dir;

    private BdbModule module(File state) {
        BdbModule bdb = new BdbModule();
        bdb.setDir(new ConfigPath("state", state.getAbsolutePath()));
        return bdb;
    }

    @Test
    public void refusesJeEnvironment() throws Exception {
        File state = dir.resolve("state").toFile();
        state.mkdirs();
        File jdb = new File(state, "00000000.jdb");
        Files.write(jdb.toPath(), new byte[] {1, 2, 3});
        BdbModule bdb = module(state);
        IllegalStateException e = assertThrows(IllegalStateException.class, bdb::start);
        assertTrue(e.getMessage().contains("BDB JE"), e.getMessage());
        assertEquals(List.of("00000000.jdb"), List.of(state.list()));
    }

    @Test
    public void springLoadsEveryProperty() throws Exception {
        File state = dir.resolve("state").toFile();
        String xml = "<?xml version='1.0' encoding='UTF-8'?>\n"
                + "<beans xmlns='http://www.springframework.org/schema/beans'"
                + " xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance'"
                + " xsi:schemaLocation='http://www.springframework.org/schema/beans"
                + " http://www.springframework.org/schema/beans/spring-beans.xsd'>\n"
                + " <bean id='bdb' class='org.archive.bdb.BdbModule'>\n"
                + "  <property name='dir'><bean class='org.archive.spring.ConfigPath'>"
                + "<property name='name' value='state'/><property name='path' value='" + state.getAbsolutePath() + "'/>"
                + "</bean></property>\n"
                + "  <property name='cachePercent' value='20'/>\n"
                + "  <property name='cacheSize' value='33554432'/>\n"
                + "  <property name='useSharedCache' value='false'/>\n"
                + "  <property name='maxLogFileSize' value='20000000'/>\n"
                + "  <property name='expectedConcurrency' value='25'/>\n"
                + "  <property name='cleanerThreads' value='2'/>\n"
                + "  <property name='evictorCoreThreads' value='2'/>\n"
                + "  <property name='evictorMaxThreads' value='4'/>\n"
                + "  <property name='useHardLinkCheckpoints' value='false'/>\n"
                + " </bean>\n"
                + "</beans>\n";
        GenericXmlApplicationContext context = new GenericXmlApplicationContext();
        context.load(new ByteArrayResource(xml.getBytes(StandardCharsets.UTF_8)));
        context.refresh();
        try {
            BdbModule bdb = context.getBean(BdbModule.class);
            bdb.start();
            bdb.openDatabase("pending", true).put(new byte[] {1}, new byte[] {2});
            assertEquals(2, bdb.openDatabase("pending", true).get(new byte[] {1})[0]);
            bdb.close();
        } finally {
            context.close();
        }
        assertTrue(new File(state, "onggi.current").exists());
    }

    @Test
    public void dropsTempMapsOnOpen() throws Exception {
        File state = dir.resolve("state").toFile();
        BdbModule bdb = module(state);
        bdb.start();
        DisposableStoredSortedMap<Long,String> temp = bdb.getStoredMap(null, Long.class, String.class, true, false);
        temp.put(1L, "a");
        String name = temp.getKeyspace().name();
        assertTrue(name.startsWith(BdbModule.TEMP_MAP_PREFIX));
        bdb.getStoredMap("kept", Long.class, String.class, false, false).put(1L, "b");
        bdb.close();

        bdb = module(state);
        bdb.start();
        assertFalse(bdb.getStore().keyspaceNames().contains(name));
        assertEquals("b", bdb.getStoredMap("kept", Long.class, String.class, false, true).get(1L));
        bdb.close();
    }

    @Test
    public void checkpointResumesTwice() throws Exception {
        File state = dir.resolve("state").toFile();
        File checkpoints = dir.resolve("checkpoints").toFile();
        BdbModule bdb = module(state);
        bdb.start();
        StoredSortedMap<String,String> map = bdb.getStoredMap("m", String.class, String.class, false, true);
        map.put("before", "1");
        Checkpoint cp = new Checkpoint();
        cp.generateFrom(new ConfigPath("checkpoints", checkpoints.getAbsolutePath()), 1);
        bdb.doCheckpoint(cp);
        map.put("after", "2");
        bdb.close();

        for (int i = 0; i < 2; i++) {
            bdb = module(state);
            bdb.setRecoveryCheckpoint(cp);
            bdb.start();
            map = bdb.getStoredMap("m", String.class, String.class, false, true);
            assertEquals("1", map.get("before"));
            assertNull(map.get("after"));
            map.put("after", "3");
            bdb.close();
        }
    }
}
