package org.archive.crawler.browser;

import org.apache.commons.httpclient.URIException;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Round-robin scheduler which limits the number of simultaneously active requests per host.
 */
public class SubrequestScheduler {
    private final Map<String, Queue<Subrequest>> queues = new HashMap<>();
    private final Map<String, Integer> activeRequestCounts = new HashMap<>();
    private final BlockingQueue<Queue<Subrequest>> readyQueues = new LinkedBlockingQueue<>();
    private int maxRequestsPerHost = 2;

    void schedule(Subrequest subrequest) throws URIException {
        synchronized (this) {
            String key = subrequest.curi.getClassKey();
            Queue<Subrequest> queue = queues.get(key);
            if (queue == null) {
                queue = new ArrayDeque<>();
                queue.add(subrequest);
                queues.put(key, queue);
                assert !queue.isEmpty();
                readyQueues.add(queue);
            } else {
                queue.add(subrequest);
            }
        }
    }

    Subrequest next() throws InterruptedException {
        Queue<Subrequest> queue = readyQueues.take();
        synchronized (this) {
            Subrequest subrequest = queue.remove();
            String key = subrequest.curi.getClassKey();
            int active = activeRequestCounts.compute(key, (k, v) -> v == null ? 1 : v + 1);
            if (queue.isEmpty()) {
                queues.remove(key);
            } else if (active < maxRequestsPerHost) {
                assert !queue.isEmpty();
                readyQueues.add(queue);
            }
            return subrequest;
        }
    }

    void finished(Subrequest subrequest) {
        synchronized (this) {
            String key = subrequest.curi.getClassKey();
            int active = activeRequestCounts.get(key) - 1;
            assert active >= 0;
            if (active == 0) {
                activeRequestCounts.remove(key);
            } else {
                activeRequestCounts.put(key, active);
            }
            Queue<Subrequest> queue = queues.get(key);
            if (queue != null && !queue.isEmpty() && active < maxRequestsPerHost) {
                assert !queue.isEmpty();
                readyQueues.add(queue);
            }
        }
    }
}
