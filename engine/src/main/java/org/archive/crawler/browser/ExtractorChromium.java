package org.archive.crawler.browser;

import org.archive.crawler.framework.CrawlController;
import org.archive.crawler.reporting.CrawlerLoggerModule;
import org.archive.io.ArchiveReader;
import org.archive.io.ArchiveReaderFactory;
import org.archive.io.HeaderedArchiveRecord;
import org.archive.modules.CrawlURI;
import org.archive.modules.extractor.ContentExtractor;
import org.archive.modules.extractor.Hop;
import org.archive.modules.extractor.LinkContext;
import org.archive.modules.recrawl.PersistLoadProcessor;
import org.archive.util.ArchiveUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.Lifecycle;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static java.util.logging.Level.WARNING;
import static org.archive.modules.recrawl.RecrawlAttributeConstants.A_WARC_FILE_OFFSET;
import static org.archive.modules.recrawl.RecrawlAttributeConstants.A_WARC_FILE_PATH;

public class ExtractorChromium extends ContentExtractor implements Lifecycle {
    private static final Logger logger = Logger.getLogger(ExtractorChromium.class.getName());

    private final SubrequestScheduler subrequestScheduler = new SubrequestScheduler();
    private final PersistLoadProcessor persistLoadProcessor;
    private final CrawlController crawlController;
    private final List<SubrequestThread> subrequestThreads = new ArrayList<>();

    private int maxResourceSize = 10 * 1024 * 1024;
    private Set<String> requestHeaderBlacklist = new HashSet<>(Arrays.asList("te", "connection", "keep-alive",
            "trailer", "transfer-encoding", "host", "upgrade-insecure-requests"));
    private final CrawlerLoggerModule crawlerLoggerModule;

    public ExtractorChromium(CrawlController crawlController, PersistLoadProcessor persistLoadProcessor, CrawlerLoggerModule crawlerLoggerModule) {
        this.crawlController = crawlController;
        this.persistLoadProcessor = persistLoadProcessor;
        this.crawlerLoggerModule = crawlerLoggerModule;
    }

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Override
    public void start() {
        super.start();
        for (int i = 0; i < 10; i++) {
            SubrequestThread thread = new SubrequestThread(crawlController, subrequestScheduler, crawlerLoggerModule, eventPublisher, i);
            subrequestThreads.add(thread);
            thread.start();
        }
    }

    @Override
    public void stop() {
        super.stop();
        for (SubrequestThread thread : subrequestThreads) {
            thread.interrupt();
        }
    }

    public int getMaxResourceSize() {
        return maxResourceSize;
    }

    /**
     * Limits the maximum size of resources replayed to the browser. A safety measure to prevent running out of memory
     * loading excessively large resources.
     */
    public void setMaxResourceSize(int maxContentSize) {
        this.maxResourceSize = maxContentSize;
    }

    public Set<String> getRequestHeaderBlacklist() {
        return requestHeaderBlacklist;
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

    static byte[] readReplayContent(CrawlURI uri, int maxLength) throws IOException {
        long length = uri.getRecorder().getResponseContentLength();
        byte[] buffer = new byte[(int) Math.min(length, maxLength)];
        try (InputStream stream = uri.getRecorder().getContentReplayInputStream()) {
            ArchiveUtils.readFully(stream, buffer);
        }
        return buffer;
    }

    class RequestInterceptor implements Consumer<ChromiumRequest> {
        private final CrawlURI uri;

        public RequestInterceptor(CrawlURI uri) {
            this.uri = uri;
        }

        @Override
        public void accept(ChromiumRequest request) {
            System.out.println("Chromium " + request.getUrl());
            logger.fine("chromium request " + request.getUrl());
            try {
                if (request.getMethod().equals("GET") && request.getUrl().equals(uri.toString())) {
                    request.fulfill(uri.getFetchStatus(), "", uri.getHttpResponseHeaders(), readReplayContent(uri, maxResourceSize));
                } else {
                    CrawlURI curi = uri.createCrawlURI(request.getUrl(), LinkContext.EMBED_MISC, Hop.EMBED);
                    curi.getAnnotations().add("subrequest");
                    curi.setClassKey(curi.getUURI().getHost());

                    if (!fulfillWithPriorCapture(request, curi)) {
                        Map<String, String> requestHeaders = new HashMap<>();
                        for (Map.Entry<String, String> entry : request.getHeaders().entrySet()) {
                            if (!requestHeaderBlacklist.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                                requestHeaders.put(entry.getKey(), entry.getValue());
                            }
                        }
                        curi.getData().put("customHttpRequestHeaders", requestHeaders);

                        subrequestScheduler.schedule(new Subrequest(request, curi, maxResourceSize));
                    }
                }
            } catch (Throwable e) {
                logger.log(WARNING, "Failed to fulfill subrequest: " + request.getMethod() + " " + request.getUrl(), e);
                request.fail("Failed");
            }
        }

        private boolean fulfillWithPriorCapture(ChromiumRequest request, CrawlURI curi) throws IOException, InterruptedException {
            if (!(request.getMethod().equals("GET") || request.getMethod().equals("HEAD"))) return false;

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
                if (request.getMethod().equals("HEAD")) {
                    body = new byte[0];
                } else {
                    long bodyLength = record.getHeader().getLength() - record.getPosition();
                    if (bodyLength > maxResourceSize) return false;
                    body = new byte[(int) bodyLength];
                    ArchiveUtils.readFully(record, body);
                }
                request.fulfill(record.getStatusCode(), "", record.getContentHeaders(), body);
                return true;
            } catch (NoSuchFileException e) {
                return false; // theoretically the file could be renamed or moved just before we open it
            }
        }
    }

}
