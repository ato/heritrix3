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

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.SortedMap;
import java.util.concurrent.atomic.AtomicLong;

import com.moaduda.onggi.Cursor;
import com.moaduda.onggi.Keyspace;
import com.sleepycat.bind.EntryBinding;
import com.sleepycat.je.DatabaseEntry;

/**
 * A {@link SortedMap} over an Onggi {@link Keyspace}, with JE's bindings turning keys and values into bytes, in
 * place of JE's {@code StoredSortedMap}. Keys sort by their bytes, as in JE.
 *
 * <p>Iterators read a batch of entries through a cursor, then close it, and reopen it after the last key read for
 * the next batch, so an iterator that is never finished or closed pins nothing. Like JE's iterators they are not
 * snapshots: they see some changes made while they run. {@link Iterator#remove()} deletes the last entry returned.
 *
 * <p>A map that allows duplicates appends an 8-byte big-endian sequence number to each key, from a counter that
 * starts above the largest one in the keyspace, so equal keys iterate in the order they were put.
 *
 * <p>{@link #size()} counts by iterating.
 */
public class StoredSortedMap<K,V> extends AbstractMap<K,V> implements SortedMap<K,V> {
    static final int BATCH = 1000;
    private static final int SUFFIX = 8;

    protected final Keyspace keyspace;
    protected final EntryBinding<K> keyBinding;
    protected final EntryBinding<V> valueBinding;
    protected final boolean duplicates;
    /** The next duplicate suffix; shared by a map and its views. */
    private final AtomicLong nextSuffix;
    /** Inclusive lower bound on key bytes (without any suffix), or null. */
    private final byte[] from;
    /** Exclusive upper bound on key bytes (without any suffix), or null. */
    private final byte[] to;

    public StoredSortedMap(Keyspace keyspace, EntryBinding<K> keyBinding, EntryBinding<V> valueBinding) {
        this(keyspace, keyBinding, valueBinding, false);
    }

    public StoredSortedMap(Keyspace keyspace, EntryBinding<K> keyBinding, EntryBinding<V> valueBinding,
            boolean duplicates) {
        this(keyspace, keyBinding, valueBinding, duplicates,
                new AtomicLong(duplicates ? largestSuffix(keyspace) + 1 : 0), null, null);
    }

    private StoredSortedMap(Keyspace keyspace, EntryBinding<K> keyBinding, EntryBinding<V> valueBinding,
            boolean duplicates, AtomicLong nextSuffix, byte[] from, byte[] to) {
        this.keyspace = keyspace;
        this.keyBinding = keyBinding;
        this.valueBinding = valueBinding;
        this.duplicates = duplicates;
        this.nextSuffix = nextSuffix;
        this.from = from;
        this.to = to;
    }

    private static long largestSuffix(Keyspace keyspace) {
        long largest = -1;
        try (Cursor cursor = keyspace.cursor()) {
            for (boolean ok = cursor.first(); ok; ok = cursor.next()) {
                byte[] key = cursor.key();
                if (key.length >= SUFFIX) {
                    largest = Math.max(largest, readLong(key, key.length - SUFFIX));
                }
            }
        }
        return largest;
    }

    private static long readLong(byte[] bytes, int at) {
        long value = 0;
        for (int i = 0; i < SUFFIX; i++) value = value << 8 | (bytes[at + i] & 0xff);
        return value;
    }

    /** The keyspace this map stores its entries in. */
    public Keyspace getKeyspace() {
        return keyspace;
    }

    protected byte[] keyBytes(Object key) {
        @SuppressWarnings("unchecked")
        K k = (K) key;
        DatabaseEntry entry = new DatabaseEntry();
        keyBinding.objectToEntry(k, entry);
        return Arrays.copyOfRange(entry.getData(), entry.getOffset(), entry.getOffset() + entry.getSize());
    }

    protected byte[] valueBytes(V value) {
        DatabaseEntry entry = new DatabaseEntry();
        valueBinding.objectToEntry(value, entry);
        return Arrays.copyOfRange(entry.getData(), entry.getOffset(), entry.getOffset() + entry.getSize());
    }

    protected K key(byte[] stored) {
        int length = duplicates ? stored.length - SUFFIX : stored.length;
        return keyBinding.entryToObject(new DatabaseEntry(stored, 0, length));
    }

    protected V value(byte[] stored) {
        return valueBinding.entryToObject(new DatabaseEntry(stored));
    }

    private boolean inRange(byte[] key) {
        return (from == null || Arrays.compareUnsigned(key, from) >= 0)
                && (to == null || Arrays.compareUnsigned(key, to) < 0);
    }

    /** Whether a stored key holds {@code key} followed by a duplicate suffix. */
    private static boolean isDuplicateOf(byte[] stored, byte[] key) {
        return stored.length == key.length + SUFFIX
                && Arrays.equals(stored, 0, key.length, key, 0, key.length);
    }

    private byte[] withSuffix(byte[] key) {
        byte[] stored = Arrays.copyOf(key, key.length + SUFFIX);
        long suffix = nextSuffix.getAndIncrement();
        for (int i = SUFFIX - 1; i >= 0; i--) {
            stored[key.length + i] = (byte) suffix;
            suffix >>>= 8;
        }
        return stored;
    }

    @Override
    public V get(Object key) {
        byte[] k = keyBytes(key);
        if (!inRange(k)) return null;
        if (!duplicates) {
            byte[] value = keyspace.get(k);
            return value == null ? null : value(value);
        }
        try (Cursor cursor = keyspace.cursor()) {
            if (cursor.seek(k) && isDuplicateOf(cursor.key(), k)) return value(cursor.value());
            return null;
        }
    }

    @Override
    public boolean containsKey(Object key) {
        byte[] k = keyBytes(key);
        if (!inRange(k)) return false;
        if (!duplicates) return keyspace.get(k) != null;
        try (Cursor cursor = keyspace.cursor()) {
            return cursor.seek(k) && isDuplicateOf(cursor.key(), k);
        }
    }

    /**
     * Stores {@code value} under {@code key} and returns the value it replaces. In a map that allows duplicates it
     * adds another entry for the key and returns null.
     */
    @Override
    public V put(K key, V value) {
        byte[] k = keyBytes(key);
        checkRange(k);
        if (duplicates) {
            keyspace.put(withSuffix(k), valueBytes(value));
            return null;
        }
        V old = get(key);
        keyspace.put(k, valueBytes(value));
        return old;
    }

    /** Stores {@code value} only if the key is absent, atomically in a map without duplicates. */
    @Override
    public V putIfAbsent(K key, V value) {
        byte[] k = keyBytes(key);
        checkRange(k);
        if (duplicates) {
            V old = get(key);
            if (old == null) put(key, value);
            return old;
        }
        byte[] v = valueBytes(value);
        while (true) {
            if (keyspace.putIfAbsent(k, v)) return null;
            V old = get(key);
            if (old != null) return old;
        }
    }

    /** Removes every entry for the key, returning the first value. */
    @Override
    public V remove(Object key) {
        byte[] k = keyBytes(key);
        if (!inRange(k)) return null;
        if (!duplicates) {
            byte[] old = keyspace.get(k);
            if (old == null) return null;
            keyspace.delete(k);
            return value(old);
        }
        V first = null;
        try (Cursor cursor = keyspace.cursor()) {
            for (boolean ok = cursor.seek(k); ok && isDuplicateOf(cursor.key(), k); ok = cursor.next()) {
                if (first == null) first = value(cursor.value());
                keyspace.delete(cursor.key());
            }
        }
        return first;
    }

    private void checkRange(byte[] key) {
        if (!inRange(key)) throw new IllegalArgumentException("key out of range");
    }

    /** Positions a cursor on the first entry in range; false when there is none. */
    private boolean first(Cursor cursor) {
        boolean ok = from == null ? cursor.first() : cursor.seek(from);
        return ok && (to == null || Arrays.compareUnsigned(cursor.key(), to) < 0);
    }

    /** Positions a cursor on the last entry in range; false when there is none. */
    private boolean last(Cursor cursor) {
        boolean ok = to == null ? cursor.last() : cursor.seekForPrev(to);
        if (ok && to != null && Arrays.equals(cursor.key(), to)) ok = cursor.prev();
        return ok && (from == null || Arrays.compareUnsigned(cursor.key(), from) >= 0);
    }

    @Override
    public boolean isEmpty() {
        try (Cursor cursor = keyspace.cursor()) {
            return !first(cursor);
        }
    }

    @Override
    public K firstKey() {
        try (Cursor cursor = keyspace.cursor()) {
            if (!first(cursor)) throw new NoSuchElementException();
            return key(cursor.key());
        }
    }

    @Override
    public K lastKey() {
        try (Cursor cursor = keyspace.cursor()) {
            if (!last(cursor)) throw new NoSuchElementException();
            return key(cursor.key());
        }
    }

    /** Counts the entries in range by iterating over them. */
    @Override
    public int size() {
        int count = 0;
        try (Cursor cursor = keyspace.cursor()) {
            for (boolean ok = first(cursor); ok; ok = cursor.next()) {
                if (to != null && Arrays.compareUnsigned(cursor.key(), to) >= 0) break;
                count++;
            }
        }
        return count;
    }

    /** Removes every entry in range; on the whole map, by truncating the keyspace. */
    @Override
    public void clear() {
        if (from == null && to == null) {
            keyspace.truncate();
            return;
        }
        Iterator<Map.Entry<K,V>> it = entrySet().iterator();
        while (it.hasNext()) {
            it.next();
            it.remove();
        }
    }

    @Override
    public Comparator<? super K> comparator() {
        return null;
    }

    private StoredSortedMap<K,V> view(byte[] lower, byte[] upper) {
        byte[] f = from;
        if (lower != null && (f == null || Arrays.compareUnsigned(lower, f) > 0)) f = lower;
        byte[] t = to;
        if (upper != null && (t == null || Arrays.compareUnsigned(upper, t) < 0)) t = upper;
        return new StoredSortedMap<K,V>(keyspace, keyBinding, valueBinding, duplicates, nextSuffix, f, t);
    }

    @Override
    public SortedMap<K,V> subMap(K fromKey, K toKey) {
        return view(keyBytes(fromKey), keyBytes(toKey));
    }

    @Override
    public SortedMap<K,V> headMap(K toKey) {
        return view(null, keyBytes(toKey));
    }

    @Override
    public SortedMap<K,V> tailMap(K fromKey) {
        return view(keyBytes(fromKey), null);
    }

    @Override
    public Set<Map.Entry<K,V>> entrySet() {
        return new AbstractSet<Map.Entry<K,V>>() {
            @Override
            public Iterator<Map.Entry<K,V>> iterator() {
                return new BatchIterator();
            }

            @Override
            public int size() {
                return StoredSortedMap.this.size();
            }

            @Override
            public boolean isEmpty() {
                return StoredSortedMap.this.isEmpty();
            }

            @Override
            public void clear() {
                StoredSortedMap.this.clear();
            }
        };
    }

    /** Reads {@link #BATCH} entries at a time, holding no cursor between batches. */
    private class BatchIterator implements Iterator<Map.Entry<K,V>> {
        private final ArrayDeque<byte[][]> batch = new ArrayDeque<>();
        /** The stored key of the last entry read into a batch, or null before the first. */
        private byte[] lastRead;
        private boolean exhausted;
        /** The stored key of the entry last returned, for {@link #remove()}. */
        private byte[] current;

        private void fill() {
            try (Cursor cursor = keyspace.cursor()) {
                boolean ok;
                if (lastRead == null) {
                    ok = first(cursor);
                } else {
                    ok = cursor.seek(lastRead);
                    if (ok && Arrays.equals(cursor.key(), lastRead)) ok = cursor.next();
                }
                while (ok && batch.size() < BATCH) {
                    byte[] key = cursor.key();
                    if (to != null && Arrays.compareUnsigned(key, to) >= 0) break;
                    batch.add(new byte[][] {key, cursor.value()});
                    lastRead = key;
                    ok = cursor.next();
                }
                if (batch.size() < BATCH) exhausted = true;
            }
        }

        @Override
        public boolean hasNext() {
            if (batch.isEmpty() && !exhausted) fill();
            return !batch.isEmpty();
        }

        @Override
        public Map.Entry<K,V> next() {
            if (!hasNext()) throw new NoSuchElementException();
            byte[][] entry = batch.remove();
            current = entry[0];
            return new AbstractMap.SimpleImmutableEntry<K,V>(key(entry[0]), value(entry[1]));
        }

        @Override
        public void remove() {
            if (current == null) throw new IllegalStateException();
            keyspace.delete(current);
            current = null;
        }
    }
}
