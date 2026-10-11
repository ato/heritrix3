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

import com.moaduda.onggi.Keyspace;
import com.moaduda.onggi.Store;
import com.sleepycat.bind.EntryBinding;

/**
 * A StoredSortedMap whose keyspace is dropped by {@link #dispose()}, for
 * transient uses such as reports.
 */
public class DisposableStoredSortedMap<K,V> extends StoredSortedMap<K,V> {
    protected Store store;

    public DisposableStoredSortedMap(Store store, Keyspace keyspace, EntryBinding<K> keyBinding,
            EntryBinding<V> valueBinding, boolean duplicates) {
        super(keyspace, keyBinding, valueBinding, duplicates);
        this.store = store;
    }

    public void dispose() {
        if (this.store != null) {
            this.store.dropKeyspace(keyspace.name());
            this.store = null;
        }
    }
}
