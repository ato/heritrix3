package org.archive.crawler.browser;

import org.apache.commons.httpclient.URIException;
import org.archive.modules.CrawlURI;

public class Subrequest {
    final ChromiumRequest browserRequest;
    final CrawlURI curi;
    final int maxResourceSize;
    final String key;

    public Subrequest(ChromiumRequest browserRequest, CrawlURI curi, int maxResourceSize) throws URIException {
        this.browserRequest = browserRequest;
        this.curi = curi;
        this.maxResourceSize = maxResourceSize;
        key = curi.getUURI().getHost();
    }
}
