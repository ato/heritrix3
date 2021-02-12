package org.archive.modules.extractor;

import junit.framework.TestCase;
import org.apache.commons.httpclient.URIException;
import org.archive.modules.CrawlMetadata;
import org.archive.modules.CrawlURI;
import org.archive.modules.DispositionChain;
import org.archive.modules.FetchChain;
import org.archive.modules.fetcher.DefaultServerCache;
import org.archive.modules.fetcher.FetchHTTP;
import org.archive.modules.fetcher.SimpleCookieStore;
import org.archive.net.UURIFactory;
import org.archive.spring.KeyedProperties;
import org.archive.util.Recorder;

import java.io.File;
import java.util.Arrays;

public class ExtractorChromiumTest extends TestCase {

    public void test() throws URIException, InterruptedException {

        FetchChain fetchChain = new FetchChain();
        FetchHTTP fetchHTTP = new FetchHTTP();
        fetchHTTP.setCookieStore(new SimpleCookieStore());
        fetchHTTP.setServerCache(new DefaultServerCache());
        CrawlMetadata uap = new CrawlMetadata();
        uap.setUserAgentTemplate("Test");
        fetchHTTP.setUserAgentProvider(uap);
        fetchHTTP.start();

        fetchChain.setProcessors(Arrays.asList(fetchHTTP));

        ExtractorChromium extractor = new ExtractorChromium(fetchChain, null, null);
        CrawlURI seed = new CrawlURI(UURIFactory.getInstance("http://localhost/"));
        seed.getOverlayNames();
        KeyedProperties.loadOverridesFrom(seed);
        Recorder recorder = new Recorder(new File("/tmp"), "x");
        Recorder.setHttpRecorder(recorder);
        seed.setRecorder(recorder);
//        fetchChain.process(seed, null);
        fetchHTTP.process(seed);
        System.out.println(seed.getRecordedSize());
        //extractor.extract(seed);
    }
}