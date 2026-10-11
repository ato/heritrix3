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

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.archive.bdb.BdbModule;
import org.archive.modules.CrawlURI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.Lifecycle;

import org.archive.bdb.StoredClassCatalog;
import org.archive.bdb.StoredSortedMap;

import com.moaduda.onggi.Keyspace;
import com.sleepycat.bind.serial.SerialBinding;
import com.sleepycat.bind.tuple.StringBinding;

/**
 * Bdb content digest history store. Must be a toplevel bean in
 * crawler-beans.cxml in order to receive {@link Lifecycle} events.
 * 
 * @see AbstractContentDigestHistory
 * @author nlevitt
 */
public class BdbContentDigestHistory extends AbstractContentDigestHistory implements Lifecycle {

    private static final Logger logger = 
            Logger.getLogger(BdbContentDigestHistory.class.getName());

    protected BdbModule bdb;
    @Autowired
    public void setBdbModule(BdbModule bdb) {
        this.bdb = bdb;
    }
    
    protected String historyDbName = "contentDigestHistory";
    public String getHistoryDbName() {
        return this.historyDbName;
    }
    public void setHistoryDbName(String name) {
        this.historyDbName = name; 
    }

    @SuppressWarnings("rawtypes")
    protected StoredSortedMap<String, Map> store;
    protected Keyspace historyDb;

    @Override
    @SuppressWarnings("rawtypes")
    public void start() {
        if (isRunning()) {
            return;
        }
        StoredClassCatalog classCatalog = bdb.getClassCatalog();
        historyDb = bdb.openDatabase(getHistoryDbName(), true);
        store = new StoredSortedMap<String, Map>(
                    historyDb,
                    new StringBinding(),
                    new SerialBinding<Map>(classCatalog, Map.class));
    }

    @Override
    public boolean isRunning() {
        return historyDb != null; 
    }

    @Override
    public void stop() {
        if (!isRunning()) {
            return;
        }
        // leave other cleanup to BdbModule
        historyDb = null;
    }
    
    public void load(CrawlURI curi) {
        // make this call in all cases so that the value is initialized and
        // WARCWriterProcessor knows it should put the info in there
        HashMap<String, Object> contentDigestHistory = curi.getContentDigestHistory();
        
        @SuppressWarnings("unchecked")
        Map<String, Object> loadedHistory = store.get(persistKeyFor(curi));
        if (loadedHistory != null) {
            if (logger.isLoggable(Level.FINER)) {
                logger.finer("loaded history by digest " + persistKeyFor(curi)
                        + " for uri " + curi + " - " + loadedHistory);
            }
            contentDigestHistory.putAll(loadedHistory);
        }
    }
    
    public void store(CrawlURI curi) {
        if (!curi.hasContentDigestHistory()
                || curi.getContentDigestHistory().isEmpty()) {
            return;
        }
        if (logger.isLoggable(Level.FINER)) {
            logger.finer("storing history by digest " + persistKeyFor(curi)
                    + " for uri " + curi + " - "
                    + curi.getContentDigestHistory());
        }
        store.put(persistKeyFor(curi), curi.getContentDigestHistory());
    }
}