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
package org.archive.crawler.frontier;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.OpenDataException;

import org.apache.commons.collections.Closure;
import org.archive.bdb.KryoBinding;
import org.archive.modules.CrawlURI;
import org.archive.util.ArchiveUtils;

import com.google.common.base.Charsets;
import com.moaduda.onggi.Cursor;
import com.moaduda.onggi.Keyspace;
import com.sleepycat.bind.EntryBinding;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.util.RuntimeExceptionWrapper;

/**
 * A keyspace-backed structure for holding ordered
 * groupings of CrawlURIs. Reading the groupings from specific
 * per-grouping (per-classKey/per-Host) starting points allows
 * this to act as a collection of independent queues.
 * 
 * <p>
 * For how the keys are made, see {@link #calculateInsertKey(CrawlURI)}.
 * A CrawlURI's holder key is its key, as a byte array.
 * 
 * <p>
 * TODO: refactor, improve naming.
 * 
 * @author gojomo
 */
public class BdbMultipleWorkQueues {
    @SuppressWarnings("unused")
    private static final long serialVersionUID = 1L;

    private static final Logger LOGGER = Logger.getLogger(BdbMultipleWorkQueues.class.getName());

    /** Keyspace holding all pending URIs, grouped in virtual queues */
    private Keyspace pendingUrisDB = null;

    /** Supporting serialization of CrawlURIs */
    private EntryBinding<CrawlURI> crawlUriBinding;

    /**
     * Create the multi queue in the given keyspace.
     */
    public BdbMultipleWorkQueues(Keyspace db) {
        this.pendingUrisDB = db;
        crawlUriBinding = new KryoBinding<CrawlURI>(CrawlURI.class);
    }

    private CrawlURI decode(byte[] value) {
        return crawlUriBinding.entryToObject(new DatabaseEntry(value));
    }

    /**
     * Delete all CrawlURIs matching the given expression.
     * 
     * @param match
     * @param queue
     * @param headKey
     * @return count of deleted items
     */
    public long deleteMatchingFromQueue(String match, String queue,
            byte[] headKey) {
        long deletedCount = 0;
        Pattern pattern = Pattern.compile(match);
        Thread.interrupted();
        try (Cursor cursor = pendingUrisDB.cursor()) {
            for (boolean ok = cursor.seek(headKey); ok; ok = cursor.next()) {
                byte[] value = cursor.value();
                if (value.length > 0) {
                    CrawlURI curi = decode(value);
                    if (!curi.getClassKey().equals(queue)) {
                        // rolled into next queue; finished with this queue
                        break;
                    }
                    if (pattern.matcher(curi.toString()).matches()) {
                        // the cursor keeps its view and moves on
                        pendingUrisDB.delete(cursor.key());
                        deletedCount++;
                    }
                }
            }
        }

        return deletedCount;
    }

    /**
     * @param m          marker or null to start with first entry
     * @param maxMatches
     * @return list of matches starting from marker position
     */
    public CompositeData getFrom(
            String m,
            int maxMatches,
            Pattern pattern,
            boolean verbose) {
        int matches = 0;
        ArrayList<String> results = new ArrayList<String>(maxMatches);

        byte[] key;
        if (m == null) {
            key = getFirstKey();
        } else {
            key = m.getBytes(); // = FrontierJMXTypes.fromString(m);
        }

        boolean ok = false;
        Thread.interrupted();
        try (Cursor cursor = pendingUrisDB.cursor()) {
            // an exact match, as JE's getSearchKey
            ok = key != null && cursor.seek(key) && Arrays.equals(cursor.key(), key);
            while (matches < maxMatches && ok) {
                byte[] value = cursor.value();
                if (value.length > 0) {
                    CrawlURI curi = decode(value);
                    if (pattern.matcher(curi.toString()).matches()) {
                        if (verbose) {
                            results.add("[" + curi.getClassKey() + "] "
                                    + curi.shortReportLine());
                        } else {
                            results.add(curi.toString());
                        }
                        matches++;
                    }
                }
                ok = cursor.next();
                if (ok) {
                    key = cursor.key();
                }
            }
        }

        if (!ok) {
            // end of scan
            m = null;
        } else {
            m = new String(key); // = FrontierJMXTypes.toString(key);
        }

        String[] arr = results.toArray(new String[results.size()]);
        CompositeData cd;
        try {
            cd = new CompositeDataSupport(
                    /* FrontierJMXTypes.URI_LIST_DATA */ null,
                    new String[] { "list", "marker" },
                    new Object[] { arr, m });
        } catch (OpenDataException e) {
            throw new IllegalStateException(e);
        }
        return cd;
    }

    /**
     * @return the key to the first item in the keyspace, or null
     */
    protected byte[] getFirstKey() {
        Thread.interrupted();
        try (Cursor cursor = pendingUrisDB.cursor()) {
            return cursor.first() ? cursor.key() : null;
        }
    }

    /**
     * Get the first item after the given queue's cap, or after the given
     * key when one is remembered from an earlier dequeue. Relies on
     * external discipline -- we'll look at the queues count of how many
     * items it has -- to avoid asking for something from a
     * range where there are no associated items --
     * otherwise could get first item of next 'queue' by mistake.
     * 
     * @param origin Key that demarks the beginning of the queue's range
     *               in <code>pendingUrisDB</code>: its cap.
     * @param after  the key of the last item dequeued from this queue, to
     *               seek past the deleted items before it; or null to start
     *               at the cap
     * @return CrawlURI, with its key as holder key, or null.
     */
    public CrawlURI get(byte[] origin, byte[] after) {
        byte[][] found = getNextNearestItem(origin, after);
        if (found == null) {
            return null;
        }
        CrawlURI retVal;
        try {
            retVal = decode(found[1]);
        } catch (ClassCastException cce) {
            Object obj = decode(found[1]);
            LOGGER.log(Level.SEVERE,
                    "see [#HER-1283]: deserialized " + obj.getClass()
                            + " has ClassLoader "
                            + obj.getClass().getClassLoader().getClass(),
                    cce);
            return null;
        } catch (RuntimeExceptionWrapper rw) {
            LOGGER.log(
                    Level.SEVERE,
                    "expected object missing in queue " +
                            BdbWorkQueue.getPrefixClassKey(origin),
                    rw);
            return null;
        }
        retVal.setHolderKey(found[0]);
        return retVal;
    }

    /** The key and value of the first item past the cap or {@code after}, or null. */
    protected byte[][] getNextNearestItem(byte[] origin, byte[] after) {
        Thread.interrupted();
        try (Cursor cursor = this.pendingUrisDB.cursor()) {
            boolean ok;
            if (after == null) {
                // get cap; origin should always point to
                // a queue-beginning cap entry (zero-length value)
                if (!cursor.seek(origin) || !Arrays.equals(cursor.key(), origin)) {
                    LOGGER.severe("queue cap missing: " + new String(origin));
                    return null;
                }
                if (cursor.value().length > 0) {
                    LOGGER.severe("queue has nonzero size: " + cursor.value().length);
                    return null;
                }
                ok = cursor.next();
            } else {
                ok = cursor.seek(after);
                if (ok && Arrays.equals(cursor.key(), after)) {
                    ok = cursor.next();
                }
            }
            if (!ok) {
                LOGGER.severe("See '1219854 NPE je-2.0 entryToObject...': nothing after "
                        + BdbWorkQueue.getPrefixClassKey(origin));
                return null;
            }
            return new byte[][] {cursor.key(), cursor.value()};
        }
    }

    /**
     * Put the given CrawlURI in at the appropriate place.
     * 
     * @param curi
     */
    public void put(CrawlURI curi, boolean overwriteIfPresent) {
        byte[] insertKey = (byte[]) curi.getHolderKey();
        if (insertKey == null) {
            insertKey = calculateInsertKey(curi);
            curi.setHolderKey(insertKey);
        }
        DatabaseEntry entry = new DatabaseEntry();
        crawlUriBinding.objectToEntry(curi, entry);
        byte[] value = Arrays.copyOfRange(entry.getData(), entry.getOffset(), entry.getOffset() + entry.getSize());
        // Output tally on avg. size if level is FINE or greater.
        if (LOGGER.isLoggable(Level.FINE)) {
            tallyAverageEntrySize(curi, value);
        }
        Thread.interrupted();
        if (overwriteIfPresent) {
            pendingUrisDB.put(insertKey, value);
        } else if (!pendingUrisDB.putIfAbsent(insertKey, value)) {
            LOGGER.log(Level.SEVERE, "URI enqueueing failed; key exists " + curi, new RuntimeException());
        }
    }

    private long entryCount = 0;
    private long entrySizeSum = 0;
    private int largestEntry = 0;

    /**
     * Log average size of database entry.
     * 
     * @param curi  CrawlURI this entry is for.
     * @param value Stored value.
     */
    private synchronized void tallyAverageEntrySize(CrawlURI curi,
            byte[] value) {
        entryCount++;
        int length = value.length;
        entrySizeSum += length;
        int avg = (int) (entrySizeSum / entryCount);
        if (entryCount % 1000 == 0) {
            LOGGER.fine("Average entry size at " + entryCount + ": " + avg);
        }
        if (length > largestEntry) {
            largestEntry = length;
            LOGGER.fine("Largest entry: " + length + " " + curi);
            if (length > (2 * avg)) {
                LOGGER.fine("excessive?");
            }
        }
    }

    /**
     * Calculate the 'origin' key for a virtual queue of items
     * with the given classKey. This origin key will be a
     * prefix of the keys for all items in the queue.
     * 
     * @param classKey String key to derive origin byte key from
     * @return a byte array key
     */
    protected static byte[] calculateOriginKey(String classKey) {
        byte[] classKeyBytes = null;
        int len = 0;
        try {
            classKeyBytes = classKey.getBytes("UTF-8");
            len = classKeyBytes.length;
        } catch (UnsupportedEncodingException e) {
            // should be impossible; all JVMs must support UTF-8
            e.printStackTrace();
        }
        byte[] keyData = new byte[len + 1];
        System.arraycopy(classKeyBytes, 0, keyData, 0, len);
        keyData[len] = 0;
        return keyData;
    }

    /**
     * Calculate the insertKey that places a CrawlURI in the
     * desired spot. First bytes are always classKey (usu. host)
     * based -- ensuring grouping by host -- terminated by a zero
     * byte. Then 8 bytes of data ensuring desired ordering
     * within that 'queue' are used. The first byte of these 8 is
     * priority -- allowing 'immediate' and 'soon' items to
     * sort above regular. Next 1 byte is 'precedence'. Last 6 bytes
     * are ordinal serial number, ensuring earlier-discovered
     * URIs sort before later.
     * 
     * NOTE: Dangers here are:
     * (1) priorities or precedences over 2^7 (signed byte comparison)
     * (2) ordinals over 2^48
     * 
     * Package access &amp; static for testing purposes.
     * 
     * @param curi
     * @return the key for the CrawlURI
     */
    protected static byte[] calculateInsertKey(CrawlURI curi) {
        byte[] classKeyBytes = null;
        int len = 0;
        classKeyBytes = curi.getClassKey().getBytes(Charsets.UTF_8);
        len = classKeyBytes.length;
        byte[] keyData = new byte[len + 9];
        System.arraycopy(classKeyBytes, 0, keyData, 0, len);
        keyData[len] = 0;
        long ordinalPlus = curi.getOrdinal() & 0x0000FFFFFFFFFFFFL;
        ordinalPlus = ((long) curi.getSchedulingDirective() << 56) | ordinalPlus;
        long rawPrecedence = curi.getPrecedence();
        if (rawPrecedence > 127) {
            LOGGER.warning("URI precedence " + rawPrecedence
                    + " exceeds maximum 127 and will be clipped"
                    + " for queue ordering: " + curi);
        }
        long precedence = Math.min(rawPrecedence, 127);
        ordinalPlus = (((precedence) & 0xFFL) << 48) | ordinalPlus;
        ArchiveUtils.longIntoByteArray(ordinalPlus, keyData, len + 1);
        return keyData;
    }

    protected static String insertKeyToString(byte[] data) {
        StringBuilder result = new StringBuilder();
        int p = findFirstZero(data);
        result.append(new String(data, 0, p));

        java.io.ByteArrayInputStream binp = new java.io.ByteArrayInputStream(data, p + 1, data.length);
        java.io.DataInputStream dinp = new java.io.DataInputStream(binp);
        long l = 0;
        try {
            l = dinp.readLong();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        result.append(" blah=").append(l);

        return result.toString();
    }

    private static int findFirstZero(byte[] b) {
        for (int i = 0; i < b.length; i++) {
            if (b[i] == 0) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Delete the given CrawlURI from persistent store. Requires
     * the key under which it was stored be available.
     * 
     * @param item
     * @return whether the item was there to delete
     */
    public boolean delete(CrawlURI item) {
        Thread.interrupted();
        if (item == null) {
            LOGGER.warning("Attempted to delete null CrawlURI from BDB; skipping.");
            return false;
        }
        Object keyObj = item.getHolderKey();
        if (keyObj == null) {
            LOGGER.warning("Cannot delete CrawlURI with null holderKey: " + item);
            return false;
        }

        if (!(keyObj instanceof byte[])) {
            LOGGER.warning("holderKey is not a byte[] for: " + item + " (actual type: "
                    + keyObj.getClass().getName() + ")");
            return false;
        }

        byte[] key = (byte[]) keyObj;
        if (!pendingUrisDB.remove(key)) {
            LOGGER.warning("Failed to delete CrawlURI from BDB: " + item + "(key="
                    + new BigInteger(key).toString(16) + ")");
            return false;
        }
        return true;
    }

    /**
     * Does nothing: the store holding the keyspace is checkpointed and
     * synced whole, by its BdbModule.
     */
    protected void sync() {
    }

    /**
     * clean up
     *
     */
    public void close() {
    }

    /**
     * Add a dummy 'cap' entry at the given insertion key, the origin of a
     * queue, so that its first item is always the entry after an entry of
     * its own.
     * 
     * @param origin key at which to insert the cap
     */
    public void addCap(byte[] origin) {
        Thread.interrupted();
        pendingUrisDB.put(origin, new byte[0]);
    }

    /**
     * Utility method to perform action for all pending CrawlURI instances.
     * 
     * @param c Closure action to perform
     */
    protected void forAllPendingDo(Closure c) {
        Thread.interrupted();
        try (Cursor cursor = pendingUrisDB.cursor()) {
            for (boolean ok = cursor.first(); ok; ok = cursor.next()) {
                byte[] value = cursor.value();
                if (value.length == 0) {
                    continue;
                }
                c.execute(decode(value));
            }
        }
    }

    /**
     * Run through all uris in the pending uris keyspace and write them to the
     * writer.
     * 
     * @param writer destination writer for writting all the uris
     * @return number of uris written to the writer
     */
    public long exportPendingUris(PrintWriter writer) {
        if (this.pendingUrisDB == null) {
            return -6L;
        }
        long uris = 0L;
        Thread.interrupted();
        try (Cursor cursor = pendingUrisDB.cursor()) {
            for (boolean ok = cursor.first(); ok; ok = cursor.next()) {
                byte[] value = cursor.value();
                if (value.length == 0) {
                    continue;
                }
                writer.println(decode(value).toString());
                ++uris;
            }
        }
        return uris;
    }
}
