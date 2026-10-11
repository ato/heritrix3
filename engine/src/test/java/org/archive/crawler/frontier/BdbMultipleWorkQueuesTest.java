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

import org.archive.bdb.BdbModule;
import org.archive.modules.CrawlURI;
import org.archive.modules.SchedulingConstants;
import org.archive.net.UURI;
import org.archive.net.UURIFactory;
import org.archive.url.URIException;
import org.archive.util.Recorder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.moaduda.onggi.Cursor;
import com.moaduda.onggi.Keyspace;
import com.moaduda.onggi.Pool;
import com.moaduda.onggi.Store;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for BdbMultipleWorkQueues functionality.
 *
 * @author gojomo
 */
public class BdbMultipleWorkQueuesTest {
    private static Logger logger =
        Logger.getLogger(BdbMultipleWorkQueuesTest.class.getName());
    @TempDir
    Path tempDir;
    @TempDir
    Path curiTempDir;

    private BdbMultipleWorkQueues pendingUris = null;
    private Store store;
    /** The keys cursors of the pending keyspace were sought to, in order. */
    private final List<byte[]> seeks = new ArrayList<byte[]>();

    protected Recorder getRecorder() throws IOException {
        if (Recorder.getHttpRecorder() == null) {
            Recorder httpRecorder = new Recorder(curiTempDir.toFile(),
                    getClass().getName(), 16 * 1024, 512 * 1024);
            Recorder.setHttpRecorder(httpRecorder);
        }

        return Recorder.getHttpRecorder();
    }

    protected CrawlURI makeCrawlURI(String uri) throws URIException,
            IOException {
        UURI uuri = UURIFactory.getInstance(uri);
        CrawlURI curi = new CrawlURI(uuri);
        curi.setClassKey("key");
        curi.setSeed(true);
        curi.setRecorder(getRecorder());
        return curi;
    }

    @BeforeEach
    protected void setUp() throws Exception {
        store = Store.open(tempDir.resolve("BdbMultipleWorkQueuesTest"),
                BdbModule.storeOptions(Pool.shared()));
        this.pendingUris = new BdbMultipleWorkQueues(new SeekRecording(store.keyspace("pending")));
    }

    @AfterEach
    protected void tearDown() throws Exception {
        if(this.pendingUris!=null)
            this.pendingUris.close();
        store.close();
    }

    /**
     * After a dequeue, a queue's next peek seeks from the key it dequeued,
     * past the deleted entries before it, rather than from its origin; an
     * insert at or before that key sends the next peek back to the origin.
     */
    @Test
    public void testPeekSeeksFromLastDequeuedKey() throws IOException {
        BdbFrontier frontier = new BdbFrontier() {
            @Override
            protected BdbMultipleWorkQueues getWorkQueues() {
                return BdbMultipleWorkQueuesTest.this.pendingUris;
            }
        };
        BdbWorkQueue queue = new BdbWorkQueue("key", frontier);
        byte[] origin = BdbMultipleWorkQueues.calculateOriginKey("key");
        for (int i = 1; i <= 3; i++) {
            CrawlURI curi = makeCrawlURI("http://archive.org/" + i);
            curi.setOrdinal(i);
            queue.insertItem(frontier, curi, false);
        }

        CrawlURI first = peek(queue, frontier, origin, "http://archive.org/1");
        queue.deleteItem(frontier, first);
        CrawlURI second = peek(queue, frontier, (byte[]) first.getHolderKey(), "http://archive.org/2");
        queue.deleteItem(frontier, second);

        // after the remembered key: it stays
        CrawlURI later = makeCrawlURI("http://archive.org/later");
        later.setOrdinal(4);
        queue.insertItem(frontier, later, false);
        // before it: the next peek starts from the origin again
        CrawlURI urgent = makeCrawlURI("http://archive.org/urgent");
        urgent.setOrdinal(5);
        urgent.setSchedulingDirective(SchedulingConstants.HIGH);
        queue.insertItem(frontier, urgent, false);
        CrawlURI peeked = peek(queue, frontier, origin, "http://archive.org/urgent");
        queue.deleteItem(frontier, peeked);

        peeked = peek(queue, frontier, (byte[]) peeked.getHolderKey(), "http://archive.org/3");
        queue.deleteItem(frontier, peeked);
        peeked = peek(queue, frontier, (byte[]) peeked.getHolderKey(), "http://archive.org/later");
    }

    /**
     * A URI inserted before the item a queue has peeked, while that item is
     * in flight, is not skipped by the peek after the item is dequeued.
     */
    @Test
    public void testInsertBeforeInFlightItemIsNotSkipped() throws IOException {
        BdbFrontier frontier = new BdbFrontier() {
            @Override
            protected BdbMultipleWorkQueues getWorkQueues() {
                return BdbMultipleWorkQueuesTest.this.pendingUris;
            }
        };
        BdbWorkQueue queue = new BdbWorkQueue("key", frontier);
        for (int i = 1; i <= 2; i++) {
            CrawlURI curi = makeCrawlURI("http://archive.org/" + i);
            curi.setOrdinal(i);
            queue.enqueue(frontier, curi);
        }
        CrawlURI first = queue.peek(frontier);
        assertEquals("http://archive.org/1", first.toString());
        CrawlURI urgent = makeCrawlURI("http://archive.org/urgent");
        urgent.setOrdinal(3);
        urgent.setSchedulingDirective(SchedulingConstants.HIGH);
        queue.enqueue(frontier, urgent);
        queue.dequeue(frontier, first);
        assertEquals("http://archive.org/urgent", queue.peek(frontier).toString());
    }

    private CrawlURI peek(BdbWorkQueue queue, BdbFrontier frontier, byte[] expectedSeek, String expectedUri)
            throws IOException {
        seeks.clear();
        CrawlURI curi = queue.peekItem(frontier);
        assertEquals(expectedUri, curi.toString());
        assertEquals(1, seeks.size());
        assertArrayEquals(expectedSeek, seeks.get(0));
        return curi;
    }

    /** A keyspace that records the keys its cursors seek to. */
    private class SeekRecording implements Keyspace {
        private final Keyspace delegate;

        SeekRecording(Keyspace delegate) {
            this.delegate = delegate;
        }

        public String name() { return delegate.name(); }
        public byte[] get(byte[] key) { return delegate.get(key); }
        public void put(byte[] key, byte[] value) { delegate.put(key, value); }
        public boolean putIfAbsent(byte[] key, byte[] value) { return delegate.putIfAbsent(key, value); }
        public void delete(byte[] key) { delegate.delete(key); }
        public boolean remove(byte[] key) { return delegate.remove(key); }
        public void truncate() { delegate.truncate(); }
        public long estimatedKeys() { return delegate.estimatedKeys(); }

        public Cursor cursor() {
            final Cursor cursor = delegate.cursor();
            return new Cursor() {
                public boolean seek(byte[] key) {
                    seeks.add(key.clone());
                    return cursor.seek(key);
                }
                public boolean seekForPrev(byte[] key) { return cursor.seekForPrev(key); }
                public boolean first() { return cursor.first(); }
                public boolean last() { return cursor.last(); }
                public boolean next() { return cursor.next(); }
                public boolean prev() { return cursor.prev(); }
                public boolean isValid() { return cursor.isValid(); }
                public byte[] key() { return cursor.key(); }
                public byte[] value() { return cursor.value(); }
                public void close() { cursor.close(); }
            };
        }
    }

    /**
     * Basic sanity checks for calculateInsertKey() -- ensure ordinal, cost,
     * and schedulingDirective have the intended effects, for ordinal values
     * up through 1/4th of the maximum (about 2^61).
     */
    @Test
    public void testCalculateInsertKey() throws URIException {
        while(Thread.interrupted()) {
            logger.warning("stray interrupt cleared");
        }

        for (long ordinalOrigin = 1; ordinalOrigin < Long.MAX_VALUE / 4; ordinalOrigin <<= 1) {
            CrawlURI curi1 =
                new CrawlURI(UURIFactory.getInstance("http://archive.org/foo"));
            curi1.setOrdinal(ordinalOrigin);
            curi1.setClassKey("foo");
            byte[] key1 =
                BdbMultipleWorkQueues.calculateInsertKey(curi1);
            CrawlURI curi2 =
                new CrawlURI(UURIFactory.getInstance("http://archive.org/bar"));
            curi2.setOrdinal(ordinalOrigin + 1);
            curi2.setClassKey("foo");
            byte[] key2 =
                BdbMultipleWorkQueues.calculateInsertKey(curi2);
            CrawlURI curi3 =
                new CrawlURI(UURIFactory.getInstance("http://archive.org/baz"));
            curi3.setOrdinal(ordinalOrigin + 2);
            curi3.setClassKey("foo");
            curi3.setSchedulingDirective(SchedulingConstants.HIGH);
            byte[] key3 =
                BdbMultipleWorkQueues.calculateInsertKey(curi3);
            CrawlURI curi4 =
                new CrawlURI(UURIFactory.getInstance("http://archive.org/zle"));
            curi4.setOrdinal(ordinalOrigin + 3);
            curi4.setClassKey("foo");
            curi4.setPrecedence(2);
            byte[] key4 =
                BdbMultipleWorkQueues.calculateInsertKey(curi4);
            CrawlURI curi5 =
                new CrawlURI(UURIFactory.getInstance("http://archive.org/gru"));
            curi5.setOrdinal(ordinalOrigin + 4);
            curi5.setClassKey("foo");
            curi5.setPrecedence(1);
            byte[] key5 =
                BdbMultipleWorkQueues.calculateInsertKey(curi5);
            // ensure that key1 (with lower ordinal) sorts before key2 (higher
            // ordinal)
            assertTrue(Arrays.compareUnsigned(key1, key2) < 0,
                    "lower ordinal sorting first (" + ordinalOrigin + ")");
            // ensure that key3 (with HIGH scheduling) sorts before key2 (even
            // though
            // it has lower ordinal)
            assertTrue(Arrays.compareUnsigned(key3, key2) < 0,
                    "lower directive sorting first (" + ordinalOrigin + ")");
            // ensure that key5 (with lower cost) sorts before key4 (even though 
            // key4  has lower ordinal and same default NORMAL scheduling directive)
            assertTrue(Arrays.compareUnsigned(key5, key4) < 0,
                    "lower cost sorting first (" + ordinalOrigin + ")");
        }
    }

    @Test
    public void testThreadInterrupt() throws InterruptedException, IOException {
        MockToeThread mockToeThread = new MockToeThread(this.pendingUris, makeCrawlURI("http://www.archive.org"));

        mockToeThread.start();

        while (mockToeThread.isAlive()) {
            Thread.sleep(100);
        }
        mockToeThread.join();
        assertNull(mockToeThread.thrownException);

    }
    class MockToeThread extends Thread {
        BdbMultipleWorkQueues pendingUris;
        CrawlURI curi;
        Exception thrownException;
        public MockToeThread(BdbMultipleWorkQueues pendingUris, CrawlURI curi) {
            this.pendingUris = pendingUris;
            this.curi = curi;
            this.thrownException = null;
        }
        @Override
        public void run() {
            this.pendingUris.put(this.curi, true);

            Thread.currentThread().interrupt();
            try {
                this.pendingUris.put(this.curi, true);
            }
            catch (RuntimeException ex) {
                this.thrownException = ex;
            }

        }
    }

}
