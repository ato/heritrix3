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
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.moaduda.onggi.Options;
import com.moaduda.onggi.Pool;
import com.moaduda.onggi.Store;
import com.moaduda.onggi.WalMode;
import com.sleepycat.bind.serial.SerialBinding;
import com.sleepycat.bind.tuple.StringBinding;

public class StoredClassCatalogTest {
    @TempDir
    Path dir;

    public static class Fetched implements Serializable {
        private static final long serialVersionUID = 1L;
        String digest;

        Fetched(String digest) {
            this.digest = digest;
        }
    }

    /**
     * In WalMode.OFF each keyspace recovers to its own last flush. A value
     * whose class was just added to the catalog, in a keyspace flushed on
     * its own soon after, must still read back after a crash: the catalog
     * syncs the store when it adds a class.
     */
    @Test
    public void valueReadsBackAfterCrashInWalModeOff() throws Exception {
        Path live = dir.resolve("live");
        Path crashed = dir.resolve("crashed");
        // A small budget, so that filling the values keyspace flushes it alone.
        Pool pool = Pool.create(1 << 20, 2);
        Options options = Options.defaults().withWalMode(WalMode.OFF).withPool(pool);
        try (Store store = Store.open(live, options)) {
            StoredClassCatalog catalog = new StoredClassCatalog(store);
            StoredSortedMap<String,Fetched> history = new StoredSortedMap<String,Fetched>(
                    store.keyspace("uri_history"), new StringBinding(),
                    new SerialBinding<Fetched>(catalog, Fetched.class));
            history.put("a", new Fetched("sha1:abc"));
            byte[] filler = new byte[1000];
            for (int i = 0; i < 2000; i++) {
                store.keyspace("uri_history").put(("filler" + i).getBytes(), filler);
            }
            // Wait for the values keyspace to flush, then crash: copy the
            // directory as a crashed process would leave it.
            long deadline = System.nanoTime() + 30_000_000_000L;
            while (store.stats().levels().stream().mapToInt(l -> l.files()).sum() == 0) {
                assertTrue(System.nanoTime() < deadline, "no flush");
                Thread.sleep(10);
            }
            copy(live, crashed);
        }
        try (Store store = Store.open(crashed, options)) {
            StoredClassCatalog catalog = new StoredClassCatalog(store);
            StoredSortedMap<String,Fetched> history = new StoredSortedMap<String,Fetched>(
                    store.keyspace("uri_history"), new StringBinding(),
                    new SerialBinding<Fetched>(catalog, Fetched.class));
            assertEquals("sha1:abc", history.get("a").digest);
        }
        pool.close();
    }

    /** Copies a live store's files, retrying if one is deleted under the copy. */
    private static void copy(Path from, Path to) throws IOException {
        while (true) {
            if (Files.exists(to)) {
                try (Stream<Path> files = Files.list(to)) {
                    for (Path f : (Iterable<Path>) files::iterator) Files.delete(f);
                }
            } else {
                Files.createDirectory(to);
            }
            try (Stream<Path> files = Files.list(from)) {
                for (Path f : (Iterable<Path>) files::iterator) {
                    if (!f.getFileName().toString().equals("onggi.lock")) {
                        Files.copy(f, to.resolve(f.getFileName()));
                    }
                }
                return;
            } catch (NoSuchFileException e) {
                // Deleted while copying: try again.
            }
        }
    }
}
