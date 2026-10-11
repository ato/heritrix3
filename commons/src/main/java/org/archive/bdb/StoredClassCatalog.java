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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import com.moaduda.onggi.Keyspace;
import com.moaduda.onggi.Store;
import com.sleepycat.bind.serial.ClassCatalog;

/**
 * The class catalog of a store, for {@code SerialBinding}: the {@code classCatalog} keyspace, holding the same
 * records as JE's {@code StoredClassCatalog}, so that values keep their bytes.
 *
 * <ul>
 * <li>Key {@code 0}: the last class ID assigned, as {@link BigInteger#toByteArray()}; it starts at 0.
 * <li>Key {@code 1} followed by a class ID: the class's {@link ObjectStreamClass}, Java-serialized.
 * <li>Key {@code 2} followed by the class name in modified UTF-8: the length of the class's current ID in one byte,
 * then the ID.
 * </ul>
 *
 * <p>A value naming a class must never be durable without that class's records, and in {@code WalMode.OFF} each
 * keyspace recovers to its own last flush, so adding a class syncs the store before its ID is returned.
 */
public class StoredClassCatalog implements ClassCatalog {
    public static final String KEYSPACE = "classCatalog";

    private static final byte LAST_CLASS_ID = 0;
    private static final byte CLASS_FORMAT = 1;
    private static final byte CLASS_INFO = 2;

    private final Store store;
    private final Keyspace keyspace;
    private final Map<String, byte[]> classIds = new HashMap<>();
    private final Map<BigInteger, ObjectStreamClass> formats = new HashMap<>();

    public StoredClassCatalog(Store store) {
        this.store = store;
        this.keyspace = store.keyspace(KEYSPACE);
        byte[] lastKey = {LAST_CLASS_ID};
        if (keyspace.get(lastKey) == null && keyspace.putIfAbsent(lastKey, new byte[1])) {
            store.sync();
        }
    }

    @Override
    public synchronized byte[] getClassID(ObjectStreamClass classFormat) throws ClassNotFoundException {
        String name = classFormat.getName();
        byte[] id = classIds.get(name);
        if (id != null) return id;
        byte[] infoKey = prefixed(CLASS_INFO, modifiedUtf8(name));
        byte[] info = keyspace.get(infoKey);
        if (info != null) {
            id = Arrays.copyOfRange(info, 1, 1 + info[0]);
            byte[] stored = keyspace.get(prefixed(CLASS_FORMAT, id));
            if (stored == null || !Arrays.equals(stored, serialize(classFormat))) id = null;
        }
        if (id == null) id = addClass(infoKey, classFormat);
        classIds.put(name, id);
        return id;
    }

    /** Assigns the next class ID to a new class or a changed format, and syncs the store. */
    private byte[] addClass(byte[] infoKey, ObjectStreamClass classFormat) {
        byte[] lastKey = {LAST_CLASS_ID};
        byte[] last = keyspace.get(lastKey);
        if (last == null) throw new IllegalStateException("Class ID not initialized");
        byte[] id = new BigInteger(last).add(BigInteger.ONE).toByteArray();
        keyspace.put(lastKey, id);
        keyspace.put(prefixed(CLASS_FORMAT, id), serialize(classFormat));
        byte[] info = new byte[1 + id.length];
        info[0] = (byte) id.length;
        System.arraycopy(id, 0, info, 1, id.length);
        keyspace.put(infoKey, info);
        store.sync();
        formats.put(new BigInteger(id), classFormat);
        return id;
    }

    @Override
    public synchronized ObjectStreamClass getClassFormat(byte[] classID) throws ClassNotFoundException {
        BigInteger key = new BigInteger(classID);
        ObjectStreamClass format = formats.get(key);
        if (format == null) {
            byte[] stored = keyspace.get(prefixed(CLASS_FORMAT, classID));
            if (stored == null) throw new ClassNotFoundException("Catalog class ID not found");
            try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(stored))) {
                format = (ObjectStreamClass) in.readObject();
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
            formats.put(key, format);
        }
        return format;
    }

    @Override
    public ClassLoader getClassLoader() {
        return null;
    }

    @Override
    public void close() {
    }

    private static byte[] prefixed(byte prefix, byte[] rest) {
        byte[] key = new byte[1 + rest.length];
        key[0] = prefix;
        System.arraycopy(rest, 0, key, 1, rest.length);
        return key;
    }

    private static byte[] serialize(Object object) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(object);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bytes.toByteArray();
    }

    /** Java's modified UTF-8, as JE's {@code UtfOps} writes it, with no length. */
    private static byte[] modifiedUtf8(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x0001 && c <= 0x007f) {
                out.write(c);
            } else if (c <= 0x07ff) {
                out.write(0xc0 | (c >> 6));
                out.write(0x80 | (c & 0x3f));
            } else {
                out.write(0xe0 | (c >> 12));
                out.write(0x80 | ((c >> 6) & 0x3f));
                out.write(0x80 | (c & 0x3f));
            }
        }
        return out.toByteArray();
    }
}
