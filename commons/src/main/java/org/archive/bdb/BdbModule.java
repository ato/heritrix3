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

import java.io.Closeable;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import org.apache.commons.io.FileUtils;
import org.archive.checkpointing.Checkpoint;
import org.archive.checkpointing.Checkpointable;
import org.archive.spring.ConfigPath;
import org.archive.util.IdentityCacheable;
import org.archive.util.ObjectIdentityBdbManualCache;
import org.archive.util.ObjectIdentityCache;
import org.archive.util.TextUtils;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.Lifecycle;

import com.moaduda.onggi.Keyspace;
import com.moaduda.onggi.Options;
import com.moaduda.onggi.Pool;
import com.moaduda.onggi.Store;
import com.moaduda.onggi.WalMode;
import com.sleepycat.bind.EntryBinding;
import com.sleepycat.bind.serial.SerialBinding;
import com.sleepycat.bind.tuple.TupleBinding;

/**
 * Module managing one Onggi store in {@link #getDir()}, shared by the beans
 * wired to it. Each former BDB JE database is a keyspace of the same name.
 *
 * <p>The class keeps its name and every bean property, so existing job
 * configurations still load: the cache properties set the memory budget of
 * the store's pool, and the JE-only properties are accepted and ignored with
 * a warning.
 *
 * @author pjack
 * @author gojomo
 */
public class BdbModule implements Lifecycle, Checkpointable, Closeable, DisposableBean {
    final private static Logger LOGGER = 
        Logger.getLogger(BdbModule.class.getName()); 

    /** Prefix of the keyspaces of {@link #getStoredMap} with no name; dropped on open. */
    public static final String TEMP_MAP_PREFIX = "tempMap-";

    /** Names of the files a store keeps in its directory. */
    private static final Pattern STORE_FILE =
        Pattern.compile("onggi\\.(lock|current|current\\.tmp)|\\d+\\.(manifest|wal|table)");

    /**
     * The pool shared by every module with {@code useSharedCache} and a
     * cache size, made at the first such module's size, as JE sizes its
     * shared cache from the first environment.
     */
    private static Pool sharedPool;

    protected ConfigPath dir = new ConfigPath("bdbmodule subdirectory","state");
    public ConfigPath getDir() {
        return dir;
    }
    public void setDir(ConfigPath dir) {
        this.dir = dir;
    }
    
    /** The memory budget of the store's pool as a percentage of the maximum heap. */
    protected int cachePercent = -1;
    public int getCachePercent() {
        return cachePercent;
    }
    public void setCachePercent(int cachePercent) {
        this.cachePercent = cachePercent;
    }
    
    /** The memory budget of the store's pool in bytes; wins over cachePercent. */
    protected int cacheSize = -1;
    public int getCacheSize() {
        return cacheSize;
    }
    public void setCacheSize(int cacheSize) {
        this.cacheSize = cacheSize;
    }

    /**
     * Whether the store shares a pool with every other BdbModule in the
     * process (the default), or has one of its own.
     */
    protected boolean useSharedCache = true; 
    public boolean getUseSharedCache() {
        return useSharedCache;
    }
    public void setUseSharedCache(boolean useSharedCache) {
        this.useSharedCache = useSharedCache;
    }

    /** Ignored: a BDB JE setting. */
    protected long maxLogFileSize = 10000000;
    public long getMaxLogFileSize() {
        return maxLogFileSize;
    }
    public void setMaxLogFileSize(long maxLogFileSize) {
        this.maxLogFileSize = maxLogFileSize;
    }
    
    /** Ignored: a BDB JE setting. */
    protected int expectedConcurrency = 64;
    public int getExpectedConcurrency() {
        return expectedConcurrency;
    }
    public void setExpectedConcurrency(int expectedConcurrency) {
        this.expectedConcurrency = expectedConcurrency;
    }
    
    /** Ignored: a BDB JE setting. */
    protected int jeCleanerThreads = -1;
    public int getCleanerThreads() {
        return jeCleanerThreads;
    }
    public void setCleanerThreads(int jeCleanerThreads) {
        this.jeCleanerThreads = jeCleanerThreads;
    }
    
    /** Ignored: a BDB JE setting. */
    protected int evictorCoreThreads = -1;    
    public int getEvictorCoreThreads() {
        return evictorCoreThreads;
    }
    public void setEvictorCoreThreads(int evictorCoreThreads) {
        this.evictorCoreThreads = evictorCoreThreads;
    }

    /** Ignored: a BDB JE setting. */
    protected int evictorMaxThreads = -1;
    public int getEvictorMaxThreads() {
        return evictorMaxThreads;
    }
    public void setEvictorMaxThreads(int evictorMaxThreads) {
        this.evictorMaxThreads = evictorMaxThreads;
    }
    
    /** Ignored: checkpoints always hard-link where they can, and copy otherwise. */
    protected boolean useHardLinkCheckpoints = true;
    public boolean getUseHardLinkCheckpoints() {
        return useHardLinkCheckpoints;
    }
    public void setUseHardLinkCheckpoints(boolean useHardLinkCheckpoints) {
        this.useHardLinkCheckpoints = useHardLinkCheckpoints;
    }
    
    private transient Store store;

    /** The pool this module made for itself, closed with it; null when shared. */
    private transient Pool ownPool;
        
    private transient StoredClassCatalog classCatalog;
    
    @SuppressWarnings("rawtypes")
    private Map<String,ObjectIdentityCache> oiCaches = 
        new ConcurrentHashMap<String,ObjectIdentityCache>();

    protected boolean isRunning = false;

    public BdbModule() {
    }
    
    public synchronized void start() {
        if (isRunning()) {
            return;
        }
        
        isRunning = true;
        
        try {
            if(recoveryCheckpoint!=null) {
                doRecover(); 
            }
            setup(getDir().getFile());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
    
    public boolean isRunning() {
        return isRunning;
    }

    public void stop() {
        isRunning = false;
    }

    /**
     * Refuses a directory holding a BDB JE environment: JE log files and no
     * Onggi store. Nothing in it is deleted.
     */
    public static void refuseJeEnvironment(File dir) {
        String[] jdb = dir.list((d, name) -> name.endsWith(".jdb"));
        if (jdb != null && jdb.length > 0 && !new File(dir, "onggi.current").exists()) {
            throw new IllegalStateException(dir + " holds a BDB JE environment (" + jdb[0]
                    + "). Heritrix now stores its data in Onggi and does not read JE data;"
                    + " use a new directory, or move the JE files away.");
        }
    }

    /** Opens an Onggi store for Heritrix in {@code dir}, refusing a JE environment. */
    public static Store openStore(File dir, Options options) throws IOException {
        org.archive.util.FileUtils.ensureWriteableDirectory(dir);
        refuseJeEnvironment(dir);
        return Store.open(dir.toPath(), options);
    }

    /** The WAL mode of every store Heritrix opens. */
    public static Options storeOptions(Pool pool) {
        return Options.defaults().withWalMode(WalMode.OFF).withPool(pool);
    }

    protected void setup(File f) throws IOException {
        warnIgnored();
        this.store = openStore(f, storeOptions(pool()));
        for (String name : store.keyspaceNames()) {
            if (name.startsWith(TEMP_MAP_PREFIX)) {
                store.dropKeyspace(name);
            }
        }
        this.classCatalog = new StoredClassCatalog(store);
    }

    /** The budget the cache properties ask for, or -1 when neither is set. */
    protected long budget() {
        if (getCacheSize() > 0) {
            if (getCachePercent() > 0) {
                LOGGER.warning("cachePercent and cacheSize are both set. Only cacheSize will be used.");
            }
            return getCacheSize();
        } else if (getCachePercent() > 0) {
            return Runtime.getRuntime().maxMemory() / 100 * getCachePercent();
        }
        return -1;
    }

    protected Pool pool() {
        long budget = budget();
        if (getUseSharedCache()) {
            if (budget < 0) {
                return Pool.shared();
            }
            synchronized (BdbModule.class) {
                if (sharedPool == null) {
                    sharedPool = Pool.create(budget, threads());
                }
                return sharedPool;
            }
        }
        if (budget < 0) {
            budget = Runtime.getRuntime().maxMemory() / 4;
        }
        ownPool = Pool.create(budget, threads());
        return ownPool;
    }

    private static int threads() {
        return Math.min(4, Runtime.getRuntime().availableProcessors());
    }

    private void warnIgnored() {
        BdbModule defaults = new BdbModule();
        warnIfChanged("maxLogFileSize", getMaxLogFileSize(), defaults.getMaxLogFileSize());
        warnIfChanged("expectedConcurrency", getExpectedConcurrency(), defaults.getExpectedConcurrency());
        warnIfChanged("cleanerThreads", getCleanerThreads(), defaults.getCleanerThreads());
        warnIfChanged("evictorCoreThreads", getEvictorCoreThreads(), defaults.getEvictorCoreThreads());
        warnIfChanged("evictorMaxThreads", getEvictorMaxThreads(), defaults.getEvictorMaxThreads());
        warnIfChanged("useHardLinkCheckpoints", getUseHardLinkCheckpoints(), defaults.getUseHardLinkCheckpoints());
    }

    private static void warnIfChanged(String property, Object value, Object defaultValue) {
        if (!value.equals(defaultValue)) {
            LOGGER.warning("BdbModule property " + property + " is a BDB JE setting and is ignored");
        }
    }

    /** The store this module opened. */
    public Store getStore() {
        if (store == null) {
            throw new IllegalStateException("BdbModule not started");
        }
        return store;
    }

    /**
     * The keyspace {@code name} of this module's store, created if needed.
     * Unless {@code usePriorData} is set, it is truncated first.
     */
    public Keyspace openDatabase(String name, boolean usePriorData) {
        Keyspace keyspace = getStore().keyspace(name);
        if (!usePriorData) {
            keyspace.truncate();
        }
        return keyspace;
    }

    public StoredClassCatalog getClassCatalog() {
        return classCatalog;
    }

    public <K extends java.io.Serializable> StoredQueue<K> getStoredQueue(String dbname, Class<K> clazz, boolean usePriorData) {
        return new StoredQueue<K>(openDatabase(dbname, usePriorData), clazz, getClassCatalog());
    }

    /**
     * Get an ObjectIdentityBdbCache, backed by the keyspace of the 
     * given name, with the given value class type. If 'recycle' is true,
     * reuse values already in the keyspace; otherwise start with an 
     * empty cache. 
     */
    public <V extends IdentityCacheable> ObjectIdentityBdbManualCache<V> getOIBCCache(String dbName, boolean recycle,
            Class<? extends V> valueClass) {
        ObjectIdentityBdbManualCache<V> oic = new ObjectIdentityBdbManualCache<V>();
        oic.initialize(getStore(), openDatabase(dbName, recycle), valueClass);
        oiCaches.put(dbName, oic);
        return oic;
    }
  
    public <V extends IdentityCacheable> ObjectIdentityCache<V> getObjectCache(String dbName, boolean recycle,
            Class<V> valueClass) {
        return getObjectCache(dbName, recycle, valueClass, valueClass);
    }
    
    /**
     * Get an ObjectIdentityCache, backed by the keyspace of the given 
     * name, with objects of the given valueClass type. If 'recycle' is
     * true, reuse values already in the keyspace; otherwise start with 
     * an empty cache. 
     */
    public <V extends IdentityCacheable> ObjectIdentityCache<V> getObjectCache(String dbName, boolean recycle,
            Class<V> declaredClass, Class<? extends V> valueClass) {
        @SuppressWarnings("unchecked")
        ObjectIdentityCache<V> oic = oiCaches.get(dbName);
        if(oic!=null) {
            return oic; 
        }
        oic =  getOIBCCache(dbName, recycle, valueClass);
        return oic; 
    }
    
    public void startCheckpoint(Checkpoint checkpointInProgress) {}

    /**
     * Syncs the object caches, then checkpoints the store into a
     * subdirectory of {@link #getDir()} named after the checkpoint.
     */
    public void doCheckpoint(final Checkpoint checkpointInProgress) throws IOException {
        for (@SuppressWarnings("rawtypes") ObjectIdentityCache oic : oiCaches.values()) {
            oic.sync();
        }
        getStore().checkpoint(new File(dir.getFile(), checkpointInProgress.getName()).toPath());
        LOGGER.fine("Finished store checkpoint.");
        
        if (checkpointInProgress.getForgetAllButLatest()) {
            File[] oldEnvCpDirs = dir.getFile().listFiles(new FilenameFilter() {
                @Override
                public boolean accept(File dir, String name) {
                    return !name.equals(checkpointInProgress.getName()) 
                            && TextUtils.matches("cp\\d{5}-\\d{14}", name);
                }
            });
            for (File d: oldEnvCpDirs) {
                FileUtils.deleteDirectory(d);
            }
        }
    }

    /**
     * Replaces the store in {@link #getDir()} with the checkpoint's: deletes
     * the store's own files, hard-links the checkpoint's tables (copying
     * where a link fails), and copies its manifest and onggi.current. The
     * store never writes the checkpoint's files, so it can be resumed again.
     */
    protected void doRecover() throws IOException {
        restore(new File(dir.getFile(), recoveryCheckpoint.getName()), dir.getFile());
    }

    /** Replaces the store in {@code target} with the one checkpointed in {@code checkpoint}. */
    public static void restore(File checkpoint, File target) throws IOException {
        if (!new File(checkpoint, "onggi.current").isFile()) {
            throw new IOException(checkpoint + " is not an Onggi checkpoint");
        }
        org.archive.util.FileUtils.ensureWriteableDirectory(target);
        File[] old = target.listFiles((d, name) -> STORE_FILE.matcher(name).matches());
        for (File f : old) {
            Files.delete(f.toPath());
        }
        File[] files = checkpoint.listFiles((d, name) -> STORE_FILE.matcher(name).matches());
        for (File f : files) {
            String name = f.getName();
            File to = new File(target, name);
            if (name.endsWith(".table")) {
                try {
                    Files.createLink(to.toPath(), f.toPath().toAbsolutePath());
                    continue;
                } catch (FileSystemException | UnsupportedOperationException e) {
                    // Another filesystem: copy instead.
                }
            } else if (!name.endsWith(".manifest") && !name.equals("onggi.current")) {
                continue;
            }
            Files.copy(f.toPath(), to.toPath(), StandardCopyOption.COPY_ATTRIBUTES);
        }
    }

    public void finishCheckpoint(Checkpoint checkpointInProgress) {}
     
    protected Checkpoint recoveryCheckpoint;
    @Autowired(required=false)
    public void setRecoveryCheckpoint(Checkpoint checkpoint) {
        this.recoveryCheckpoint = checkpoint; 
    }

    public void close() {
        if (store == null) {
            return;
        }
        
        for(@SuppressWarnings("rawtypes") ObjectIdentityCache cache : oiCaches.values()) {
            try {
                cache.close();
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Error closing oiCache " + cache, e);
            }
        }
        oiCaches.clear();

        try {
            store.close();
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error closing store.", e);
        }
        store = null;
        if (ownPool != null) {
            ownPool.close();
            ownPool = null;
        }
    }

    /** uniqueness serial number for temp map keyspaces */
    protected long sn = 0;
        
    /**
     * Creates a keyspace-backed DisposableStoredSortedMap for transient 
     * reporting requirements. Calling the returned map's dispose()
     * method when done drops the keyspace. 
     * 
     * @param <K>
     * @param <V>
     * @param dbName Keyspace name to use; if null a name will be synthesized
     * @param keyClass Class of keys; should be a Java primitive type
     * @param valueClass Class of values; may be any serializable type
     * @param allowDuplicates whether duplicate keys allowed
     */
    public synchronized <K,V> DisposableStoredSortedMap<K, V> getStoredMap(String dbName, Class<K> keyClass, Class<V> valueClass, boolean allowDuplicates, boolean usePriorData) {
        if(dbName==null) {
            dbName = TEMP_MAP_PREFIX+System.identityHashCode(this)+"-"+sn;
            sn++;
        }
        Keyspace keyspace = openDatabase(dbName, usePriorData);
        EntryBinding<V> valueBinding = TupleBinding.getPrimitiveBinding(valueClass);
        if(valueBinding == null) {
            valueBinding = new SerialBinding<V>(classCatalog, valueClass);
        }
        return new DisposableStoredSortedMap<K, V>(getStore(), keyspace,
                TupleBinding.getPrimitiveBinding(keyClass), valueBinding, allowDuplicates);
    }
    
    @Override
    public void destroy() throws Exception {
        close();
    }

}
