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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.SortedMap;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.moaduda.onggi.Options;
import com.moaduda.onggi.Store;
import com.sleepycat.bind.tuple.LongBinding;
import com.sleepycat.bind.tuple.StringBinding;

/**
 * Runs the same random operations on a {@link StoredSortedMap} and a
 * {@link TreeMap}, and on views of both, and compares them after each.
 */
public class StoredSortedMapTest {
    @TempDir
    Path dir;

    private static final int OPS = 4000;

    @Test
    public void matchesTreeMap() {
        long seed = Long.getLong("heritrix.seed", System.nanoTime());
        Random random = new Random(seed);
        try (Store store = Store.open(dir, Options.defaults())) {
            StoredSortedMap<Long,String> map = new StoredSortedMap<Long,String>(
                    store.keyspace("map"), new LongBinding(), new StringBinding());
            TreeMap<Long,String> model = new TreeMap<Long,String>();
            // Enough entries that iterations cross the wrapper's batch boundaries.
            for (int i = 0; i < 3 * StoredSortedMap.BATCH; i++) {
                long key = random.nextInt(3000) - 1000;
                model.put(key, "p" + i);
                map.put(key, "p" + i);
            }
            for (int i = 0; i < OPS; i++) {
                // Mostly within a range smaller than OPS, so keys repeat and views are full.
                long key = random.nextInt(3000) - 1000;
                String value = "v" + i;
                String context = "seed " + seed + ", op " + i;
                switch (random.nextInt(14)) {
                case 0: case 1: case 2: case 3:
                    assertEquals(model.put(key, value), map.put(key, value), context);
                    break;
                case 4:
                    assertEquals(model.putIfAbsent(key, value), map.putIfAbsent(key, value), context);
                    break;
                case 5:
                    assertEquals(model.remove(key), map.remove(key), context);
                    break;
                case 6:
                    assertEquals(model.get(key), map.get(key), context);
                    assertEquals(model.containsKey(key), map.containsKey(key), context);
                    break;
                case 7: {
                    long to = key + random.nextInt(2000);
                    compare(model.subMap(key, to), map.subMap(key, to), context);
                    break;
                }
                case 8:
                    compare(model.headMap(key), map.headMap(key), context);
                    break;
                case 9:
                    compare(model.tailMap(key), map.tailMap(key), context);
                    break;
                case 10: {
                    // Iterator.remove of every n-th entry of a view, across batch boundaries.
                    long to = key + 1500 + random.nextInt(1500);
                    int every = 1 + random.nextInt(4);
                    removeEvery(model.subMap(key, to), every);
                    removeEvery(map.subMap(key, to), every);
                    break;
                }
                case 11:
                    if (random.nextInt(20) == 0) {
                        long to = key + random.nextInt(200);
                        model.subMap(key, to).clear();
                        map.subMap(key, to).clear();
                    }
                    break;
                case 12:
                    if (random.nextInt(200) == 0) {
                        model.clear();
                        map.clear();
                    }
                    break;
                default:
                    assertEquals(model.isEmpty(), map.isEmpty(), context);
                    if (!model.isEmpty()) {
                        assertEquals(model.firstKey(), map.firstKey(), context);
                        assertEquals(model.lastKey(), map.lastKey(), context);
                    }
                }
            }
            compare(model, map, "seed " + seed + " at the end");
        }
    }

    private static <K,V> void compare(SortedMap<K,V> expected, SortedMap<K,V> actual, String context) {
        assertEquals(new ArrayList<Map.Entry<K,V>>(expected.entrySet()),
                new ArrayList<Map.Entry<K,V>>(actual.entrySet()), context);
        assertEquals(new ArrayList<K>(expected.keySet()), new ArrayList<K>(actual.keySet()), context);
        assertEquals(new ArrayList<V>(expected.values()), new ArrayList<V>(actual.values()), context);
        assertEquals(expected.size(), actual.size(), context);
        assertEquals(expected.isEmpty(), actual.isEmpty(), context);
        if (expected.isEmpty()) {
            assertThrows(NoSuchElementException.class, actual::firstKey, context);
            assertThrows(NoSuchElementException.class, actual::lastKey, context);
        } else {
            assertEquals(expected.firstKey(), actual.firstKey(), context);
            assertEquals(expected.lastKey(), actual.lastKey(), context);
        }
    }

    private static <K,V> void removeEvery(SortedMap<K,V> map, int every) {
        int n = 0;
        for (Iterator<K> it = map.keySet().iterator(); it.hasNext(); n++) {
            it.next();
            if (n % every == 0) it.remove();
        }
    }

    @Test
    public void duplicatesIterateInInsertionOrder() {
        long seed = Long.getLong("heritrix.seed", System.nanoTime());
        Random random = new Random(seed);
        try (Store store = Store.open(dir, Options.defaults())) {
            StoredSortedMap<Long,String> map = new StoredSortedMap<Long,String>(
                    store.keyspace("dups"), new LongBinding(), new StringBinding(), true);
            // A key maps to its values in the order they were put.
            TreeMap<Long,List<String>> model = new TreeMap<Long,List<String>>();
            for (int i = 0; i < 3 * StoredSortedMap.BATCH; i++) {
                long key = random.nextInt(500);
                model.computeIfAbsent(key, k -> new ArrayList<String>()).add("p" + i);
                map.put(key, "p" + i);
            }
            for (int i = 0; i < OPS; i++) {
                long key = random.nextInt(500);
                String value = "v" + random.nextInt(10);
                String context = "seed " + seed + ", op " + i;
                switch (random.nextInt(8)) {
                case 0: case 1: case 2: case 3:
                    assertNull(map.put(key, value), context);
                    model.computeIfAbsent(key, k -> new ArrayList<String>()).add(value);
                    break;
                case 4: {
                    List<String> values = model.remove(key);
                    assertEquals(values == null ? null : values.get(0), map.remove(key), context);
                    break;
                }
                case 5: {
                    List<String> values = model.get(key);
                    assertEquals(values == null ? null : values.get(0), map.get(key), context);
                    assertEquals(values != null, map.containsKey(key), context);
                    break;
                }
                case 6: {
                    // headMap(now) iterated with Iterator.remove, as WorkQueueFrontier does with futureUris.
                    if (random.nextInt(10) == 0) {
                        Iterator<Map.Entry<Long,String>> it = map.headMap(key).entrySet().iterator();
                        while (it.hasNext()) {
                            Map.Entry<Long,String> e = it.next();
                            List<String> values = model.get(e.getKey());
                            assertEquals(values.remove(0), e.getValue(), context);
                            if (values.isEmpty()) model.remove(e.getKey());
                            it.remove();
                        }
                    }
                    break;
                }
                default:
                    assertEquals(flatten(model.headMap(key)), new ArrayList<Map.Entry<Long,String>>(
                            map.headMap(key).entrySet()), context);
                }
            }
            assertEquals(flatten(model), new ArrayList<Map.Entry<Long,String>>(map.entrySet()), "seed " + seed);
        }
        // A reopened map adds duplicates after the ones already there.
        try (Store store = Store.open(dir, Options.defaults())) {
            StoredSortedMap<Long,String> map = new StoredSortedMap<Long,String>(
                    store.keyspace("dups"), new LongBinding(), new StringBinding(), true);
            map.clear();
            map.put(1L, "a");
            map.put(1L, "b");
        }
        try (Store store = Store.open(dir, Options.defaults())) {
            StoredSortedMap<Long,String> map = new StoredSortedMap<Long,String>(
                    store.keyspace("dups"), new LongBinding(), new StringBinding(), true);
            map.put(1L, "c");
            assertEquals(List.of("a", "b", "c"), new ArrayList<String>(map.values()));
        }
    }

    private static List<Map.Entry<Long,String>> flatten(SortedMap<Long,List<String>> model) {
        List<Map.Entry<Long,String>> entries = new ArrayList<Map.Entry<Long,String>>();
        for (Map.Entry<Long,List<String>> e : model.entrySet()) {
            for (String v : e.getValue()) entries.add(Map.entry(e.getKey(), v));
        }
        return entries;
    }
}
