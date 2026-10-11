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
package org.archive.crawler.util;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.archive.bdb.BdbModule;
import org.archive.checkpointing.Checkpoint;
import org.archive.checkpointing.Checkpointable;
import org.json.JSONException;
import org.json.JSONObject;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.Lifecycle;

import st.ata.util.FPGenerator;

import com.moaduda.onggi.Cursor;
import com.moaduda.onggi.Keyspace;
import com.moaduda.onggi.Pool;
import com.moaduda.onggi.Store;
import com.moaduda.onggi.StoreException;
import com.sleepycat.bind.tuple.LongBinding;
import com.sleepycat.je.DatabaseEntry;


/**
 * A BDB implementation of an AlreadySeen list.
 * 
 * This implementation performs adequately without blowing out 
 * the heap. See
 * <a href="http://crawler.archive.org/cgi-bin/wiki.pl?AlreadySeen">AlreadySeen</a>.
 * 
 * <p>Makes keys that have URIs from same server close to each other.  Mercator
 * and 2.3.5 'Elminating Already-Visited URLs' in 'Mining the Web' by Soumen
 * Chakrabarti talk of a two-level key with the first 24 bits a hash of the
 * host plus port and with the last 40 as a hash of the path.  Testing
 * showed adoption of such a scheme halving lookup times (Tutilhis implementation
 * actually concatenates scheme + host in first 24 bits and path + query in
 * trailing 40 bits).
 * 
 * @author stack
 * @version $Date$, $Revision$
 */
public class BdbUriUniqFilter extends SetBasedUriUniqFilter 
implements Lifecycle, Checkpointable, BeanNameAware, DisposableBean {
    private static final long serialVersionUID = -8099357538178524011L;

    private static Logger logger =
        Logger.getLogger(BdbUriUniqFilter.class.getName());

    protected boolean createdEnvironment = false;
    /** The store and pool the standalone constructor opened, closed with this filter. */
    protected transient Store ownStore = null;
    protected transient Pool ownPool = null;
    protected transient Keyspace alreadySeen = null;
    static protected final byte[] ZERO_LENGTH_VALUE = new byte[0];
    private static final String DB_NAME = "alreadySeenUrl";
    protected AtomicLong count = new AtomicLong(0);
    private long aggregatedLookupTime = 0;
    
    private static final String COLON_SLASH_SLASH = "://";
    
    protected BdbModule bdb;
    @Autowired
    public void setBdbModule(BdbModule bdb) {
        this.bdb = bdb;
    }
    
    protected String beanName; 
    public void setBeanName(String name) {
        this.beanName = name;
    }
    
    public BdbUriUniqFilter() {
    }
    
    protected boolean isRunning = false; 
    public void start() {
        if(isRunning()) {
            return; 
        }
        boolean isRecovery = (recoveryCheckpoint != null);
        initialize(bdb.openDatabase(DB_NAME, isRecovery));
        if(isRecovery) {
            JSONObject json = recoveryCheckpoint.loadJson(beanName);
            try {
                count.set(json.getLong("count"));
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }           
        }
        isRunning = true; 
    }
    
    public boolean isRunning() {
        return isRunning;
    }

    public void stop() {
        if(!isRunning()) {
            return; 
        }
        // XXX do sync? currently happens in close()
        isRunning = false; 
    }
    
    public void destroy() {
        close();
    }
    
    /**
     * Constructor.
     * 
     * Only used for testing; usually no-arg constructor is used, and
     * environment provided by injected BdbModule. 
     * 
     * @param bdbEnv The directory that holds the bdb environment. Will
     * make a database under here if doesn't already exit.  Otherwise
     * reopens any existing dbs.
     * @throws IOException
     */
    public BdbUriUniqFilter(File bdbEnv)
    throws IOException {
        this(bdbEnv, -1);
    }
    
    /**
     * Constructor.
     * 
     * Only used for testing; usually no-arg constructor is used, and
     * store provided by injected BdbModule. 
     * 
     * @param bdbEnv The directory that holds the store. Will make one
     * here if it doesn't already exist, and empties its seen set.
     * @param cacheSizePercentage Percentage of the maximum heap the store's
     * pool may use. Pass -1 to use the shared pool.
     * @throws IOException
     */
    public BdbUriUniqFilter(File bdbEnv, final int cacheSizePercentage)
    throws IOException {
        super();
        if (cacheSizePercentage > 0 && cacheSizePercentage < 100) {
            ownPool = Pool.create(Runtime.getRuntime().maxMemory() / 100 * cacheSizePercentage,
                    Math.min(4, Runtime.getRuntime().availableProcessors()));
        }
        try {
            ownStore = BdbModule.openStore(bdbEnv,
                    BdbModule.storeOptions(ownPool != null ? ownPool : Pool.shared()));
        } catch (IOException | RuntimeException e) {
            if (ownPool != null) ownPool.close();
            throw e;
        }
        createdEnvironment = true;
        Keyspace db = ownStore.keyspace(DB_NAME);
        db.truncate();
        initialize(db);
    }

    /**
     * Method shared by constructors.
     */
    protected void initialize(Keyspace db) {
        open(db);
    }

    /**
     * Opens the already-seen set in the passed keyspace, after deserializing
     * an instance of this class.
     */
    public void reopen(Keyspace db) {
        open(db);
    }
    
    protected void open(final Keyspace db) {
        this.alreadySeen = db;
    }
    
    public synchronized void close() {
        logger.fine("Count of alreadyseen on close " + count.get());
        // Only close the store if it was opened by the standalone
        // constructor; a BdbModule closes its own store.
        if (ownStore != null) {
            try {
                ownStore.close();
            } catch (StoreException e) {
                logger.severe(e.getMessage());
            }
            ownStore = null;
        }
        if (ownPool != null) {
            ownPool.close();
            ownPool = null;
        }
    }
    
    /**
     * Onggi does not count cache misses, so this is always 0. Kept for
     * scripts that call it.
     */
    public long getCacheMisses() {
        return 0;
    }
    
    public long getLastCacheMissDiff() {
        return 0;
    }
    
    /**
     * Create fingerprint.
     * Pubic access so test code can access createKey.
     * @param uri URI to fingerprint.
     * @return Fingerprint of passed <code>url</code>.
     */
    public static long createKey(CharSequence uri) {
        String url = uri.toString();
        long schemeAuthorityKeyPart = calcSchemeAuthorityKeyBytes(url);
        return schemeAuthorityKeyPart | (FPGenerator.std40.fp(url) >>> 24);
    }

    protected static long calcSchemeAuthorityKeyBytes(String url) {
        int index = url.indexOf(COLON_SLASH_SLASH);
        if (index > 0) {
            index = url.indexOf('/', index + COLON_SLASH_SLASH.length());
        }
        CharSequence schemeAuthority = (index == -1)? url: url.subSequence(0, index);
        return FPGenerator.std24.fp(schemeAuthority);
    }

    protected boolean setAdd(CharSequence uri) {
        byte[] key = keyBytes(createKey(uri));
        long started = 0;
        if (logger.isLoggable(Level.FINE)) {
            started = System.currentTimeMillis();
        }
        boolean added;
        try {
            added = alreadySeen.putIfAbsent(key, ZERO_LENGTH_VALUE);
        } catch (StoreException e) {
            logger.severe(e.getMessage());
            return true;
        }
        if (logger.isLoggable(Level.FINE)) {
            aggregatedLookupTime +=
                (System.currentTimeMillis() - started);
        }
        if (added) {
            count.incrementAndGet();
            if (logger.isLoggable(Level.FINE)) {
                final int logAt = 10000;
                if (count.get() > 0 && ((count.get() % logAt) == 0)) {
                    logger.fine("Average lookup " +
                        (aggregatedLookupTime / logAt) + "ms.");
                    aggregatedLookupTime = 0;
                }
            }
        }
        return added;
    }

    /** A key as LongBinding writes it. */
    private static byte[] keyBytes(long key) {
        DatabaseEntry entry = new DatabaseEntry();
        LongBinding.longToEntry(key, entry);
        return entry.getData();
    }

    protected long setCount() {
        return count.get();
    }

    protected boolean setRemove(CharSequence uri) {
        boolean removed;
        try {
            removed = alreadySeen.remove(keyBytes(createKey(uri)));
        } catch (StoreException e) {
            logger.severe(e.getMessage());
            return false;
        }
        if (removed) {
            count.decrementAndGet();
        }
        return removed;
    }
    
    public long flush() {
        // We always write but this might be place to do the sync
        // when checkpointing?  TODO.
        return 0;
    }

    // Checkpointable
    // CrawlController's only interest is in knowing that a Checkpoint is
    // being recovered
    public void startCheckpoint(Checkpoint checkpointInProgress) {}
    public void doCheckpoint(Checkpoint checkpointInProgress) throws IOException {
        JSONObject json = new JSONObject();
        try {
            json.put("count", setCount());
            checkpointInProgress.saveJson(beanName, json);
        } catch (JSONException e) {
            // impossible
            throw new RuntimeException(e);
        }
    }
    public void finishCheckpoint(Checkpoint checkpointInProgress) {}
    protected Checkpoint recoveryCheckpoint;
    public void setRecoveryCheckpoint(Checkpoint recoveryCheckpoint) {
        this.recoveryCheckpoint = recoveryCheckpoint;
    }

    /**
     * Forget all entries that match the scheme+host+port of the given url, so
     * that they can be crawled again if discovered again. Expensive operation.
     * 
     * <p>
     * Because of the way keys are calculated, scheme+host+port is the only
     * grouping of urls that is feasible to forget in bulk. See
     * {@link #createKey(CharSequence)}
     * 
     * <p>
     * WARNING: Value collisions in this 24-bit schemeAuthority part are going
     * to be fairly common, by 'birthday problem' over 50% likely to show up
     * with as few as 2^12 unique schemeAuthority strings. So the forgetting may
     * forget other hosts.
     * 
     * @param url
     *            whose scheme+host+port should be forgotten (remainder of url
     *            is ignored)
     */
    public void forgetAllSchemeAuthorityMatching(String url) {
        byte[] schemeAuthorityKeyBytes = keyBytes(calcSchemeAuthorityKeyBytes(url));
        long forgottenCount = 0l;
        Cursor cursor = alreadySeen.cursor();
        try {
            for (boolean valid = cursor.seek(schemeAuthorityKeyBytes); valid; valid = cursor.next()) {
                byte[] keyData = cursor.key();
                if (keyData[0] == schemeAuthorityKeyBytes[0]
                        && keyData[1] == schemeAuthorityKeyBytes[1]
                        && keyData[2] == schemeAuthorityKeyBytes[2]) {
                    if (alreadySeen.remove(keyData)) {
                        forgottenCount++;
                    }
                } else {
                    break;
                }
            }
        } finally {
            cursor.close();
        }
        long newCount = count.addAndGet(-forgottenCount);
        logger.info("forgot " + forgottenCount + " urls from scheme+authority of url " + url + " (leaving " + newCount + " urls from other scheme+authorities)");
    }
    
} //EOC