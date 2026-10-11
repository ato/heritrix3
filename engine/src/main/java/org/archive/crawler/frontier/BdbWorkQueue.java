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
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.archive.bdb.AutoKryo;
import org.archive.crawler.frontier.precedence.HighestUriQueuePrecedencePolicy;
import org.archive.crawler.frontier.precedence.SimplePrecedenceProvider;
import org.archive.modules.CrawlURI;
import org.archive.modules.fetcher.FetchStats;
import org.archive.util.ArchiveUtils;


/**
 * One independent queue of items with the same 'classKey' (eg host).
 * @author gojomo
 */
public class BdbWorkQueue extends WorkQueue
implements Serializable {
    private static final long serialVersionUID = 1L;
    private static Logger LOGGER =
        Logger.getLogger(BdbWorkQueue.class.getName());


    /**
     * All items in this queue have this same 'origin'
     * prefix to their keys.
     */
    private byte[] origin;

    /**
     * The key of the last item dequeued (deleted) from this queue, so that
     * the next peek seeks past the deleted items at the queue's head instead
     * of from its origin; null to seek from the origin. Not saved with the
     * queue: after a resume the first peek seeks from the origin.
     */
    private transient volatile byte[] lastDequeued;

    /**
     * Whether an item was inserted at or before the peeked item while it
     * was in flight. Dequeuing the peeked item then leaves an item before
     * its key, so its key is not remembered.
     */
    private transient boolean insertedBeforePeeked;

    /**
     * Create a virtual queue inside the given BdbMultipleWorkQueues 
     * 
     * @param classKey
     */
    public BdbWorkQueue(String classKey, BdbFrontier frontier) {
        super(classKey);
        this.origin = BdbMultipleWorkQueues.calculateOriginKey(classKey);
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine(getPrefixClassKey(this.origin) + " " + classKey);
        }
        // add the queue-front 'cap' entry; see...
        // http://sourceforge.net/tracker/index.php?func=detail&aid=1262665&group_id=73833&atid=539102
        frontier.getWorkQueues().addCap(origin);
    }

    protected long deleteMatchingFromQueue(final WorkQueueFrontier frontier,
            final String match) throws IOException {
        final BdbMultipleWorkQueues queues = ((BdbFrontier) frontier)
            .getWorkQueues();
        lastDequeued = null;
        return queues.deleteMatchingFromQueue(match, classKey, origin);
    }

    protected void deleteItem(final WorkQueueFrontier frontier,
            final CrawlURI peekItem) throws IOException {
        final BdbMultipleWorkQueues queues = ((BdbFrontier) frontier)
            .getWorkQueues();
        if (queues.delete(peekItem)) {
            lastDequeued = insertedBeforePeeked ? null : (byte[]) peekItem.getHolderKey();
        }
    }

    protected CrawlURI peekItem(final WorkQueueFrontier frontier)
    throws IOException {
        final BdbMultipleWorkQueues queues = ((BdbFrontier) frontier)
            .getWorkQueues();
        CrawlURI curi = null;
        int tries = 1;
        insertedBeforePeeked = false;
        while(true) {
            byte[] after = tries == 1 ? lastDequeued : null;
            curi = queues.get(origin, after);
            
            // ensure CrawlURI, if any,  came from acceptable range: 
            if(curi != null && !ArchiveUtils.startsWith((byte[]) curi.getHolderKey(),origin)) {
                LOGGER.severe(
                    "inconsistency: "+classKey+"("+
                    getPrefixClassKey(origin)+") with " + getCount() + " items gave "
                    + curi +"("+getPrefixClassKey((byte[]) curi.getHolderKey()));
                // clear curi to allow retry from the origin
                curi = null; 
                lastDequeued = null;
            }
            
            if (curi!=null) {
                // success
                break;
            }
            
            if (tries>3) {
                LOGGER.severe("no item where expected in queue "+classKey);
                break;
            }
            tries++;
            LOGGER.severe("Trying get #" + Integer.toString(tries)
                    + " in queue " + classKey + " with " + getCount()
                    + " items using key "
                    + getPrefixClassKey(origin));
        }
 
        return curi;
    }

    protected void insertItem(final WorkQueueFrontier frontier,
            final CrawlURI curi, boolean overwriteIfPresent) throws IOException {
        final BdbMultipleWorkQueues queues = ((BdbFrontier) frontier)
            .getWorkQueues();
        queues.put(curi, overwriteIfPresent);
        byte[] key = (byte[]) curi.getHolderKey();
        byte[] last = lastDequeued;
        if (last != null && Arrays.compareUnsigned(key, last) <= 0) {
            // sorts before the remembered head: seek from the origin again
            lastDequeued = null;
        }
        CrawlURI peeked = peekItem;
        if (peeked != null && Arrays.compareUnsigned(key, (byte[]) peeked.getHolderKey()) <= 0) {
            insertedBeforePeeked = true;
        }
        if (LOGGER.isLoggable(Level.FINE)) {
            LOGGER.fine("Inserted into " + getPrefixClassKey(this.origin) +
                " (count " + Long.toString(getCount())+ "): " +
                    curi.toString());
        }
    }
    
    /**
     * @param byteArray Byte array to get hex string of.
     * @return Hex string of passed in byte array (Used logging
     * key-prefixes).
     */
    protected static String getPrefixClassKey(final byte [] byteArray) {
        int zeroIndex = 0;
        while(byteArray[zeroIndex]!=0) {
            zeroIndex++;
        }
        try {
            return new String(byteArray,0,zeroIndex,"UTF-8");
        } catch (UnsupportedEncodingException e) {
            // should be impossible; UTF-8 always available
            e.printStackTrace();
            return e.getMessage();
        }
    }
    
    // Kryo support
    public static void autoregisterTo(AutoKryo kryo) {
        kryo.register(BdbWorkQueue.class);
        kryo.autoregister(FetchStats.class); 
        kryo.autoregister(HashSet.class);
        kryo.autoregister(SimplePrecedenceProvider.class);
        kryo.autoregister(byte[].class);
        kryo.autoregister(HighestUriQueuePrecedencePolicy.HighestUriPrecedenceProvider.class);
    }
}
