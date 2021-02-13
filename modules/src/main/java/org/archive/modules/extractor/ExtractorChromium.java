package org.archive.modules.extractor;

import com.google.common.collect.Maps;
import com.google.common.util.concurrent.Striped;
import org.archive.browser.ChromiumBrowser;
import org.archive.browser.ChromiumRequest;
import org.archive.browser.ChromiumTab;
import org.archive.io.ArchiveReader;
import org.archive.io.ArchiveReaderFactory;
import org.archive.io.HeaderedArchiveRecord;
import org.archive.modules.CrawlURI;
import org.archive.modules.DispositionChain;
import org.archive.modules.FetchChain;
import org.archive.modules.recrawl.PersistLoadProcessor;
import org.archive.util.ArchiveUtils;
import org.archive.util.Recorder;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static java.util.logging.Level.WARNING;
import static org.archive.modules.recrawl.RecrawlAttributeConstants.A_WARC_FILE_OFFSET;
import static org.archive.modules.recrawl.RecrawlAttributeConstants.A_WARC_FILE_PATH;

public class ExtractorChromium extends ContentExtractor {
    private static final Logger logger = Logger.getLogger(ExtractorChromium.class.getName());
    private static final AtomicLong serial = new AtomicLong(0);

    private final FetchChain fetchChain;
    private final DispositionChain dispositionChain;
    private final PersistLoadProcessor persistLoadProcessor;

    private int maxContentSize = 10 * 1024 * 1024;
    private Set<String> requestHeaderBlacklist = new HashSet<>(Arrays.asList("te", "connection", "keep-alive",
            "trailer", "transfer-encoding", "host", "upgrade-insecure-requests"));

    private final Striped<Semaphore> hostSemaphores = Striped.semaphore(1024, 2);

    public ExtractorChromium(FetchChain fetchChain, DispositionChain dispositionChain,
                             PersistLoadProcessor persistLoadProcessor) {
        this.fetchChain = fetchChain;
        this.dispositionChain = dispositionChain;
        this.persistLoadProcessor = persistLoadProcessor;
    }

    /**
     * Limits the maximum size of resources replayed to the browser. A safety measure to prevent running out of memory
     * loading excessively large resources.
     */
    public void setMaxResourceSize(int maxContentSize) {
        this.maxContentSize = maxContentSize;
    }

    /**
     * Request headers that will be stripped from the browser's requests. Names must be in lowercase.
     */
    public void setRequestHeaderBlacklist(Set<String> requestHeaderBlacklist) {
        this.requestHeaderBlacklist = requestHeaderBlacklist;
    }

    @Override
    protected boolean shouldExtract(CrawlURI uri) {
        return uri.getContentType().toLowerCase(Locale.ROOT).startsWith("text/html");
    }

    @Override
    protected boolean innerExtract(CrawlURI uri) {
        try (ChromiumBrowser browser = new ChromiumBrowser();
             ChromiumTab tab = browser.createTab()) {
            tab.interceptRequests(new RequestInterceptor(uri));
            tab.navigate(uri.toString()).get();

            // TODO: js behaviors

            for (String link : tab.extractLinks()) {
                addOutlink(uri, link, LinkContext.NAVLINK_MISC, Hop.NAVLINK);
            }
        } catch (IOException | InterruptedException | ExecutionException e) {
            logger.log(WARNING, "Extracting with Chromium failed", e);
        }

        return false; // allow other extractors to still run
    }

    private class RequestInterceptor implements Consumer<ChromiumRequest> {
        private final CrawlURI uri;

        public RequestInterceptor(CrawlURI uri) {
            this.uri = uri;
        }

        @Override
        public void accept(ChromiumRequest request) {
            logger.fine("chromium request " + request.url());
            try {
                if (request.method().equals("GET") && request.url().equals(uri.toString())) {
                    request.fulfill(uri.getFetchStatus(), "", uri.getHttpResponseHeaders(), readReplayContent(uri));
                } else {
                    CrawlURI curi = uri.createCrawlURI(request.url(), LinkContext.EMBED_MISC, Hop.EMBED);
                    if (!fulfillWithPriorCapture(request, curi)) {
                        fetchImmediately(request, curi);
                    }
                }
            } catch (Throwable e) {
                logger.log(WARNING, "Failed to fulfill subrequest: " + request.method() + " " + request.url(), e);
                request.fail("Failed");
            }
        }

        private void fetchImmediately(ChromiumRequest request, CrawlURI curi) throws InterruptedException, IOException {
            curi.getData().put("customHttpRequestHeaders", Maps.filterKeys(request.headers(),
                    name -> !requestHeaderBlacklist.contains(name.toLowerCase(Locale.ROOT))));

            // FIXME: temporary mechanism for limiting the number of simultaneous connections per host
            // it'd be better to use some sort of queue instead of blocking threads but this was easy to implement
            Semaphore semaphore = hostSemaphores.get(curi.getUURI().getHost());
            semaphore.acquire();
            try {
                Recorder oldRecorder = Recorder.getHttpRecorder();
                Recorder recorder = new Recorder(new File("/tmp"), "hc" + serial.incrementAndGet());
                try {
                    Recorder.setHttpRecorder(recorder);
                    curi.setRecorder(recorder);
                    fetchChain.process(curi, null);
                    // FIXME: what if fetchStatus is negative?
                    // FIXME: what do we do if DNS or robots preconditions weren't met?
                    request.fulfill(curi.getFetchStatus(), "", curi.getHttpResponseHeaders(), readReplayContent(curi));
                    dispositionChain.process(curi, null);
                } finally {
                    Recorder.setHttpRecorder(oldRecorder);
                    recorder.cleanup();
                }
            } finally {
                semaphore.release();
            }
        }

        private boolean fulfillWithPriorCapture(ChromiumRequest request, CrawlURI curi) throws IOException, InterruptedException {
            if (!(request.method().equals("GET") || request.method().equals("HEAD"))) return false;

            // check the history db for any previous time we've crawled this
            persistLoadProcessor.process(curi);
            HashMap<String, Object>[] fetchHistory = curi.getFetchHistory();
            if (fetchHistory == null || fetchHistory.length == 0) return false;
            String path = (String) fetchHistory[0].get(A_WARC_FILE_PATH);
            Long offset = (Long) fetchHistory[0].get(A_WARC_FILE_OFFSET);
            if (path == null || offset == null) return false;

            // the file might still be open so if we can't find it try the .open version
            File file = new File(path);
            if (!file.exists()) file = new File(path + ".open");
            if (!file.exists()) return false;

            try (SeekableByteChannel channel = Files.newByteChannel(file.toPath())) {
                channel.position(offset);
                ArchiveReader reader = ArchiveReaderFactory.get(path, Channels.newInputStream(channel), false);
                HeaderedArchiveRecord record = new HeaderedArchiveRecord(reader.get(), true);
                byte[] body;
                if (request.method().equals("HEAD")) {
                    body = new byte[0];
                } else {
                    long bodyLength = record.getHeader().getLength() - record.getPosition();
                    if (bodyLength > maxContentSize) return false;
                    body = new byte[(int) bodyLength];
                    ArchiveUtils.readFully(record, body);
                }
                request.fulfill(record.getStatusCode(), "", record.getContentHeaders(), body);
                return true;
            } catch (NoSuchFileException e) {
                return false; // theoretically the file could be renamed or moved just before we open it
            }
        }

        private byte[] readReplayContent(CrawlURI uri) throws IOException {
            long length = uri.getRecorder().getResponseContentLength();
            byte[] buffer = new byte[(int) Math.min(length, maxContentSize)];
            try (InputStream stream = uri.getRecorder().getContentReplayInputStream()) {
                ArchiveUtils.readFully(stream, buffer);
            }
            return buffer;
        }
    }
}
