package org.archive.modules.browser;

import org.archive.modules.CrawlURI;
import org.archive.modules.DispositionChain;
import org.archive.modules.FetchChain;
import org.archive.util.Recorder;

import java.io.IOException;

public class Subrequest {
    private final ChromiumRequest browserRequest;
    final CrawlURI curi;
    private final FetchChain fetchChain;
    private final DispositionChain dispositionChain;
    private final int maxResourceSize;

    public Subrequest(ChromiumRequest browserRequest, CrawlURI curi, FetchChain fetchChain, DispositionChain dispositionChain, int maxResourceSize) {
        this.browserRequest = browserRequest;
        this.curi = curi;
        this.fetchChain = fetchChain;
        this.dispositionChain = dispositionChain;
        this.maxResourceSize = maxResourceSize;
    }

    void execute() throws InterruptedException {
        curi.setRecorder(Recorder.getHttpRecorder());
        fetchChain.process(curi, null);
        try {
            browserRequest.fulfill(curi.getFetchStatus(), "", curi.getHttpResponseHeaders(), ExtractorChromium.readReplayContent(curi, maxResourceSize));
        } catch (IOException e) {
            e.printStackTrace();
        }
        dispositionChain.process(curi, null);
    }
}
