package org.archive.crawler.browser;

import org.archive.crawler.event.CrawlURIDispositionEvent;
import org.archive.crawler.framework.CrawlController;
import org.archive.crawler.reporting.CrawlerLoggerModule;
import org.archive.modules.CrawlURI;
import org.archive.util.ArchiveUtils;
import org.archive.util.Recorder;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.util.logging.Level;

import static org.archive.crawler.event.CrawlURIDispositionEvent.Disposition.FAILED;
import static org.archive.crawler.event.CrawlURIDispositionEvent.Disposition.SUCCEEDED;
import static org.archive.modules.fetcher.FetchStatusCodes.S_DEFERRED;

/**
 * Worker thread for processing subrequests.
 */
// TODO: unify this with ToeThread? They do roughly the same thing.
public class SubrequestThread extends Thread {
    private final int serialNumber;
    private final SubrequestScheduler scheduler;
    private final CrawlController crawlController;
    private final ApplicationEventPublisher eventPublisher;
    private final CrawlerLoggerModule loggerModule;

    SubrequestThread(CrawlController crawlController, SubrequestScheduler scheduler,
                     CrawlerLoggerModule loggerModule, ApplicationEventPublisher eventPublisher, int serialNumber) {
        super(SubrequestThread.class.getName() + " #" + serialNumber);
        this.crawlController = crawlController;
        this.scheduler = scheduler;
        this.eventPublisher = eventPublisher;
        this.loggerModule = loggerModule;
        this.serialNumber = serialNumber;
        setDaemon(true);
    }

    @Override
    public void run() {
        Recorder recorder = new Recorder(crawlController.getScratchDir().getFile(), "hsr" + serialNumber,
                crawlController.getRecorderOutBufferBytes(), crawlController.getRecorderInBufferBytes());
        try {
            Recorder.setHttpRecorder(recorder);
            while (true) {
                ArchiveUtils.continueCheck();
                Subrequest subrequest = scheduler.next();
                if (subrequest == null) return;
                CrawlURI curi = subrequest.curi;
                process(subrequest, curi);
            }
        } catch (InterruptedException e) {
            // just exit
        } finally {
            recorder.closeRecorders();
        }
    }

    private void process(Subrequest subrequest, CrawlURI curi) throws InterruptedException {
        System.out.println("SRT start " + curi);
        try {
            while (true) {
                curi.setRecorder(Recorder.getHttpRecorder());
                crawlController.getFetchChain().process(curi, null);

                if (curi.getFetchStatus() != S_DEFERRED || !curi.hasPrerequisiteUri()) break;

                System.out.println("prereq " + curi.getPrerequisiteUri());
                process(null, curi.getPrerequisiteUri());
                curi.processingCleanup();
                curi.clearPrerequisiteUri();
            }

            if (subrequest != null) {
                try {
                    if (curi.getFetchStatus() < 0) {
                        subrequest.browserRequest.fail(curi.getFetchStatus());
                    } else {
                        subrequest.browserRequest.fulfill(curi.getFetchStatus(), "", curi.getHttpResponseHeaders(),
                                ExtractorChromium.readReplayContent(curi, subrequest.maxResourceSize));
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            crawlController.getDispositionChain().process(curi, null);
        } finally {
            System.out.println("SRT finished " + curi + " " + curi.getFetchStatus());
            if (subrequest != null) scheduler.finished(subrequest);
            eventPublisher.publishEvent(new CrawlURIDispositionEvent(this, curi, curi.isSuccess() ? SUCCEEDED : FAILED));
            curi.aboutToLog();
            loggerModule.getUriProcessing().log(Level.INFO,
                    curi.getUURI().toString(), new Object[]{curi});

            // tell the frontier not to bother with uri, we've already got it
            crawlController.getFrontier().considerIncluded(curi);
        }
    }
}
