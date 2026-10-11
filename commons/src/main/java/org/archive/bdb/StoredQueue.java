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

import java.io.Serializable;
import java.util.AbstractQueue;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import com.moaduda.onggi.Keyspace;
import com.sleepycat.bind.EntryBinding;
import com.sleepycat.bind.serial.SerialBinding;
import com.sleepycat.bind.tuple.TupleBinding;

/**
 * Queue backed by a StoredSortedMap over an Onggi keyspace. 
 * 
 * @author gojomo
 *
 * @param <E>
 */
public class StoredQueue<E extends Serializable> extends AbstractQueue<E>  {
    @SuppressWarnings("unused")
    private static final Logger logger =
        Logger.getLogger(StoredQueue.class.getName());

    protected transient StoredSortedMap<Long,E> queueMap; // Long -> E
    protected AtomicLong tailIndex; // next spot for insert
    protected transient volatile E peekItem = null;
    
    /**
     * Create a StoredQueue backed by the given keyspace. 
     * 
     * The Class of values to be queued may be provided; there is only a 
     * benefit when a primitive type is specified. A StoredClassCatalog
     * must be provided if a primitive type is not supplied. 
     * 
     * @param keyspace
     * @param clsOrNull 
     * @param classCatalog
     */
    public StoredQueue(Keyspace keyspace, Class<E> clsOrNull, StoredClassCatalog classCatalog) {
        hookupDatabase(keyspace, clsOrNull, classCatalog);
        tailIndex = new AtomicLong(queueMap.isEmpty() ? 0L : queueMap.lastKey()+1);
    }

    /**
     * @param keyspace
     * @param clsOrNull
     * @param classCatalog
     */
    public void hookupDatabase(Keyspace keyspace, Class<E> clsOrNull, StoredClassCatalog classCatalog) {
        EntryBinding<E> valueBinding = TupleBinding.getPrimitiveBinding(clsOrNull);
        if(valueBinding == null) {
            valueBinding = new SerialBinding<E>(classCatalog, clsOrNull);
        }
        queueMap = new StoredSortedMap<Long,E>(
                keyspace,
                TupleBinding.getPrimitiveBinding(Long.class),
                valueBinding);
    }

    @Override
    public Iterator<E> iterator() {
        return queueMap.values().iterator();
    }

    @Override
    public int size() {
        try {
            Thread.interrupted();
            return Math.max(0, 
                    (int)(tailIndex.get() 
                          - queueMap.firstKey())); 
        } catch (IllegalStateException ise) {
            return 0; 
        } catch (NoSuchElementException nse) {
            return 0;
        } catch (NullPointerException npe) {
            return 0;
        }
    }
    
    @Override
    public boolean isEmpty() {
        if(peekItem!=null) {
            return false;
        }
        try {
            return queueMap.isEmpty();
        } catch (IllegalStateException de) {
            return true;
        }
    }

    public boolean offer(E o) {
        long targetIndex = tailIndex.getAndIncrement();
        queueMap.put(targetIndex, o);
        return true;
    }

    public synchronized E peek() {
        if(peekItem == null) {
            if(queueMap.isEmpty()) {
                return null; 
            }
            peekItem = queueMap.remove(queueMap.firstKey());
        }
        return peekItem; 
    }

    public synchronized E poll() {
        E head = peek();
        peekItem = null;
        return head; 
    }

    /** Does nothing: the queue's store is synced and closed by its BdbModule. */
    public void close() {
    }
}