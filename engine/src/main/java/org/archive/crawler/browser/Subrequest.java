package org.archive.crawler.browser;

import org.archive.modules.CrawlURI;
import org.archive.modules.DispositionChain;
import org.archive.modules.FetchChain;
import org.archive.util.Recorder;

import java.io.IOException;

public class Subrequest {
    final ChromiumRequest browserRequest;
    final CrawlURI curi;
    final int maxResourceSize;

    public Subrequest(ChromiumRequest browserRequest, CrawlURI curi, int maxResourceSize) {
        this.browserRequest = browserRequest;
        this.curi = curi;
        this.maxResourceSize = maxResourceSize;
    }
}
