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


import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.logging.ConsoleHandler;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.SerializationUtils;
import org.archive.bdb.BdbModule;
import org.archive.bdb.StoredClassCatalog;
import org.archive.bdb.StoredSortedMap;
import org.archive.modules.CrawlURI;
import org.archive.util.ArchiveUtils;
import org.archive.util.FileUtils;
import org.archive.util.OneLineSimpleLogger;
import org.archive.util.SURT;
import org.archive.util.iterator.LineReadingIterator;
import org.json.JSONObject;

import com.moaduda.onggi.Pool;
import com.moaduda.onggi.Store;
import com.sleepycat.bind.serial.SerialBinding;
import com.sleepycat.bind.tuple.StringBinding;

/**
 * Superclass for Processors which utilize an Onggi store for URI state
 * (including most notably history) persistence.
 * 
 * @author gojomo
 */
public abstract class PersistProcessor extends AbstractPersistProcessor {

    @SuppressWarnings("unused")
    private static final long serialVersionUID = 1L;

    private static final Logger logger =
        Logger.getLogger(PersistProcessor.class.getName());

    /** name of history keyspace */
    public static final String URI_HISTORY_DBNAME = "uri_history";

    public PersistProcessor() {
    }
    
    /**
     * Return a preferred String key for persisting the given CrawlURI's
     * AList state. 
     * 
     * @param curi CrawlURI
     * @return String key
     */
    public static String persistKeyFor(CrawlURI curi) {
        // use a case-sensitive SURT for uniqueness and sorting benefits
        return persistKeyFor(curi.getUURI().toString());
    }

    public static String persistKeyFor(String uri) {
        // use a case-sensitive SURT for uniqueness and sorting benefits
        return SURT.fromURI(uri,true);
    }

    /**
     * Copies entries from an existing store's history to a new one. If
     * historyMap is not provided, only logs the entries that would have been 
     * copied. A BDB JE environment is refused.
     * 
     * @param sourceDir existing store directory
     * @param historyMap new history map (or null for a dry run)
     * @return number of records
     */
    private static int copyPersistEnv(File sourceDir, StoredSortedMap<String,Map> historyMap) 
    throws IOException {
        int count = 0;

        // open the source store's history, copying entries to the target
        Store sourceStore = setupCopyEnvironment(sourceDir, true);
        StoredClassCatalog sourceClassCatalog = new StoredClassCatalog(sourceStore);
        StoredSortedMap<String,Map> sourceHistoryMap = new StoredSortedMap<String,Map>(
                sourceStore.keyspace(URI_HISTORY_DBNAME),
                new StringBinding(), new SerialBinding<Map>(sourceClassCatalog,
                        Map.class));

        Iterator<Entry<String,Map>> iter = sourceHistoryMap.entrySet().iterator();
        while (iter.hasNext()) {
            Entry<String,Map> item = iter.next(); 
            if (logger.isLoggable(Level.FINE)) {
                logger.fine(item.getKey() + " " + new JSONObject(item.getValue()));
            }
            
            if (historyMap != null) {
                historyMap.put(item.getKey(), item.getValue());
            }
            count++;
        }
        sourceStore.close();
        
        return count;
    }

    /**
     * Populates an environment db from a persist log. If historyMap is
     * not provided, only logs the entries that would have been populated.
     * 
     * @param persistLogReader
     *            persist log
     * @param historyMap
     *            new environment db (or null for a dry run)
     * @return number of records
     * @throws UnsupportedEncodingException
     */
    private static int populatePersistEnvFromLog(BufferedReader persistLogReader, StoredSortedMap<String,Map> historyMap) 
    throws UnsupportedEncodingException {
        int count = 0;

        Iterator<String> iter = new LineReadingIterator(persistLogReader);
        while (iter.hasNext()) {
            String line = iter.next(); 
            if (line.length() == 0) {
                continue;
            }
            String[] splits = line.split(" ");
            if (splits.length != 2) {
                logger.severe("bad line has " + splits.length + " fields (should be 2): " + line);
                continue;
            }

            Map alist;
            try {
                alist = (Map) SerializationUtils.deserialize(Base64.decodeBase64(splits[1].getBytes("UTF-8")));
            } catch (Exception e) {
                logger.severe("caught exception " + e + " deserializing line: " + line);
                continue;
            }

            if (logger.isLoggable(Level.FINE)) {
                logger.fine(splits[0] + " " + ArchiveUtils.prettyString(alist));
            }

            if (historyMap != null) try {
                historyMap.put(splits[0], alist);
            } catch (Exception e) {
                logger.log(Level.SEVERE, "caught exception after loading " + count + 
                        " urls from the persist log (perhaps crawl was stopped by user?)", e);
                IOUtils.closeQuietly(persistLogReader);

                // seems to finish most cleanly when we return rather than throw something
                return count;
            }

            count++;
        }
        IOUtils.closeQuietly(persistLogReader);
        
        return count;
    }

    /**
     * Populates a new store's history from an old store or a persist
     * log. If path to new store is not provided, only logs the entries 
     * that would have been populated.
     * 
     * @param sourcePath
     *            source of old entries: can be a path to an existing
     *            store directory, or a URL or path to a persist log
     * @param envFile
     *            path to new store directory (or null for a dry run)
     * @return number of records
     * @throws IOException
     */
    public static int populatePersistEnv(String sourcePath, File envFile)
        throws IOException {
        int count = 0;
        StoredSortedMap<String,Map> historyMap = null;
        Store targetStore = null;

        if (envFile != null) {
            // set up target store
            FileUtils.ensureWriteableDirectory(envFile);
            targetStore = setupCopyEnvironment(envFile);
            StoredClassCatalog classCatalog = new StoredClassCatalog(targetStore);
            historyMap = new StoredSortedMap<String,Map>(
                    targetStore.keyspace(URI_HISTORY_DBNAME), 
                    new StringBinding(), new SerialBinding<Map>(classCatalog,
                        Map.class));
        }

        try {
            count = copyPersistSourceToHistoryMap(new File(sourcePath), historyMap);
        } finally {
            // in finally block so that we unlock the target env even if we
            // failed to populate it
            if (envFile != null) {
                logger.info(count + " records imported from " + sourcePath + " to store " + envFile);
                targetStore.close();
            } else {
                logger.info(count + " records found in " + sourcePath);
            }
        }

        return count;
    }

    /**
     * Populates a given StoredSortedMap (history map) from an old 
     * store or a persist log. If a map is not provided, only 
     * logs the entries that would have been populated. A BDB JE
     * environment is refused.
     * 
     * @param sourceFile
     *            source of old entries: can be a path to an existing
     *            store directory or persist log
     * @param historyMap
     *            map to populate (or null for a dry run)
     * @return number of records
     * @throws IOException
     */
    public static int copyPersistSourceToHistoryMap(File sourceFile,
            StoredSortedMap<String, Map> historyMap) throws IOException {
        // delegate depending on the source
        if (sourceFile.isDirectory()) {
            return copyPersistEnv(sourceFile, historyMap);
        } else {
            BufferedReader persistLogReader = ArchiveUtils.getBufferedReader(sourceFile);
            return populatePersistEnvFromLog(persistLogReader, historyMap);
        }
    }

    /**
     * Populates a given StoredSortedMap (history map) from an old persist log.
     * If a map is not provided, only logs the entries that would have been
     * populated.
     * 
     * @param sourceUrl
     *            url of source persist log
     * @param historyMap
     *            map to populate (or null for a dry run)
     * @return number of records
     * @throws IOException
     */
    public static int copyPersistSourceToHistoryMap(URL sourceUrl,
            StoredSortedMap<String, Map> historyMap) throws IOException {
        BufferedReader persistLogReader = ArchiveUtils
                .getBufferedReader(sourceUrl);
        return populatePersistEnvFromLog(persistLogReader, historyMap);
    }
    
    /**
     * Utility main for importing a log into an Onggi store or copying
     * history between stores (2 arguments), or simply dumping a log
     * to stderr in a more readable format (1 argument). 
     * 
     * @param args command-line arguments
     * @throws IOException
     */
    public static void main(String[] args) throws IOException {
        Handler handler = new ConsoleHandler();
        handler.setLevel(Level.ALL);
        handler.setFormatter(new OneLineSimpleLogger());
        logger.addHandler(handler);
        logger.setUseParentHandlers(false);

        if (args.length == 2) {
            logger.setLevel(Level.INFO);
            populatePersistEnv(args[0], new File(args[1]));
        } else if (args.length == 1) {
            logger.setLevel(Level.FINE);
            populatePersistEnv(args[0], null);
        } else {
            System.out.println("Arguments: ");
            System.out.println("    source [target]");
            System.out.println(
                "...where source is either a txtser log file or Onggi store dir");
            System.out.println(
                "and target, if present, is an Onggi store dir. ");
            return;
        }
    }

    public static Store setupCopyEnvironment(File env) throws IOException {
        return setupCopyEnvironment(env, false);
    }
    
    /** Opens the store in {@code env} for the copy tools, refusing a JE environment. */
    public static Store setupCopyEnvironment(File env, boolean readOnly) throws IOException {
        BdbModule.refuseJeEnvironment(env);
        return Store.open(env.toPath(),
                BdbModule.storeOptions(Pool.shared()).withReadOnly(readOnly).withCreateIfMissing(!readOnly));
    }
}