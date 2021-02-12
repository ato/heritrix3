package org.archive.modules.extractor;

import org.apache.commons.httpclient.Header;
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
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static java.util.logging.Level.WARNING;
import static org.archive.modules.recrawl.RecrawlAttributeConstants.A_WARC_FILE_OFFSET;
import static org.archive.modules.recrawl.RecrawlAttributeConstants.A_WARC_FILE_PATH;

public class ExtractorChromium extends ContentExtractor {
    private static final Logger logger = Logger.getLogger(ExtractorChromium.class.getName());
    private final FetchChain fetchChain;
    private final DispositionChain dispositionChain;
    private final PersistLoadProcessor persistLoadProcessor;
    private int maxContentSize = 10 * 1024 * 1024;
    private static final AtomicLong serial = new AtomicLong(0);

    public ExtractorChromium(FetchChain fetchChain, DispositionChain dispositionChain, PersistLoadProcessor persistLoadProcessor) {
        this.fetchChain = fetchChain;
        this.dispositionChain = dispositionChain;
        this.persistLoadProcessor = persistLoadProcessor;
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

        return false; // FIXME: double-check this, I think we want to return false to run the classic extractor too?
    }

    private class RequestInterceptor implements Consumer<ChromiumRequest> {
        private final CrawlURI uri;

        public RequestInterceptor(CrawlURI uri) {
            this.uri = uri;
        }

        @Override
        public void accept(ChromiumRequest request) {
            // TODO: we should cap the number of these we do in parallel (maybe dispatch to a thread pool?)

            logger.fine("chromium request " + request.url());
            try {
                if (request.method().equals("GET") && request.url().equals(uri.toString())) {
                    request.fulfill(uri.getFetchStatus(), "", uri.getHttpResponseHeaders().entrySet(), readReplayContent(uri));
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
            // TODO: politeness or at least limit the number of connections per host?
            Recorder oldRecorder = Recorder.getHttpRecorder();
            Recorder recorder = new Recorder(new File("/tmp"), "hc" + serial.incrementAndGet());
            try {
                Recorder.setHttpRecorder(recorder);
                curi.setRecorder(recorder);
                fetchChain.process(curi, null);
                request.fulfill(curi.getFetchStatus(), "", curi.getHttpResponseHeaders().entrySet(), readReplayContent(curi));
                dispositionChain.process(curi, null);
            } finally {
                Recorder.setHttpRecorder(oldRecorder);
                recorder.cleanup();
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

            // the warc file might have been renamed from .open
            // TODO: probably we should store the non-.open version of the filename
            File file = new File(path);
            if (path.endsWith(".open") && !file.exists()) {
                file = new File(path.substring(0, path.length() - ".open".length()));
            }
            if (!file.exists()) return false;

            // TODO: handle race here
            try (SeekableByteChannel channel = Files.newByteChannel(file.toPath())) {
                channel.position(offset);
                ArchiveReader reader = ArchiveReaderFactory.get(path.replaceFirst("\\.open$", ""), Channels.newInputStream(channel), false);
                HeaderedArchiveRecord record = new HeaderedArchiveRecord(reader.get(), true);
                Header[] headers = record.getContentHeaders();
                Map<String, String> headerMap = new HashMap<>();
                for (Header header : headers) {
                    headerMap.put(header.getName(), header.getValue());
                }

                byte[] body;
                if (request.method().equals("HEAD")) {
                    body = new byte[0];
                } else {
                    long bodyLength = record.getHeader().getLength() - record.getPosition();
                    if (bodyLength > maxContentSize) return false;
                    body = new byte[(int) bodyLength];
                    ArchiveUtils.readFully(record, body);
                }
                request.fulfill(record.getStatusCode(), "", headerMap.entrySet(), body);
                return true;
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
