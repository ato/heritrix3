package org.archive.browser;

import junit.framework.TestCase;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

public class ChromiumBrowserTest extends TestCase {
    public void test() throws IOException, ExecutionException, InterruptedException {
        try (ChromiumBrowser browser = new ChromiumBrowser();
             ChromiumTab tab = browser.createTab()) {
            tab.interceptRequests(request -> {
                System.out.println(request.url());
                Map<String,String> headers = new HashMap<>();
                headers.put("Content-Type", "text/html");
                request.fulfill(200, "", headers, "<img src=hello.jpg>".getBytes(StandardCharsets.UTF_8));
            });
            tab.navigate("http://localhost/").get();
        }
    }
}