package org.archive.modules.browser;

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

    synchronized void schedule(Subrequest subrequest) throws URIException {
        String key = subrequest.curi.getClassKey();
        Queue<Subrequest> queue = queues.get(key);
        if (queue == null) {
            queue = new ArrayDeque<>();
            queues.put(key, queue);
            readyQueues.add(queue);
        }
        queue.add(subrequest);
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
                readyQueues.add(queue);
            }
            return subrequest;
        }
    }

    synchronized void finished(Subrequest subrequest) {
        String key = subrequest.curi.getClassKey();
        int active = activeRequestCounts.compute(key, (k, v) -> v == null ? -1 : v - 1);
        assert active >= 0;
        Queue<Subrequest> queue = queues.get(key);
        if (queue != null && !queue.isEmpty() && active < maxRequestsPerHost) {
            readyQueues.add(queue);
        }
    }
}
