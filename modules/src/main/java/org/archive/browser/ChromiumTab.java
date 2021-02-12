package org.archive.browser;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.Closeable;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static java.util.logging.Level.FINE;

/**
 * Notionally a browser tab. At the protocol level this a devtools 'target'.
 */
public class ChromiumTab implements Closeable {
    private static final Logger logger = Logger.getLogger(ChromiumTab.class.getName());
    private final ChromiumBrowser browser;
    private final String targetId;
    private final String sessionId;
    private Consumer<ChromiumRequest> requestInterceptor;
    private CompletableFuture<Double> loadFuture;
    private CompletableFuture<Void> networkIdleFuture;
    private boolean closed;

    ChromiumTab(ChromiumBrowser browser) {
        this.browser = browser;

        targetId = browser.call("Target.createTarget", new JSONObject().put("url", "about:blank")
                .put("width", 1366).put("height", 768)).getString("targetId");
        sessionId = browser.call("Target.attachToTarget", new JSONObject().put("targetId", targetId)
                .put("flatten", true)).getString("sessionId");
        browser.sessionEventHandlers.put(sessionId, this::handleEvent);
        call("Page.enable", new JSONObject()); // for loadEventFired
        call("Page.setLifecycleEventsEnabled", new JSONObject().put("enabled", true)); // for networkidle
    }

    public JSONObject call(String method, JSONObject params) {
        synchronized (this) {
            if (closed) throw new IllegalStateException("closed");
        }
        return browser.call(sessionId, method, params);
    }

    private void handleEvent(JSONObject event) {
        JSONObject params = event.getJSONObject("params");
        switch (event.getString("method")) {
            case "Fetch.requestPaused":
                ChromiumRequest request = new ChromiumRequest(this, params.getString("requestId"), params.getJSONObject("request"), params.getString("resourceType"));
                if (requestInterceptor != null) {
                    try {
                        requestInterceptor.accept(request);
                    } catch (Throwable t) {
                        if (!request.handled) {
                            request.fail("Failed");
                        }
                        throw t;
                    }
                }
                if (!request.handled) {
                    request.continueNormally();
                }
                break;
            case "Page.loadEventFired":
                if (loadFuture != null) {
                    loadFuture.complete(params.getDouble("timestamp"));
                }
                break;
            case "Page.lifecycleEvent":
                String eventName = params.getString("name");
                if (networkIdleFuture != null && eventName.equals("networkIdle") && params.getString("frameId").equals(targetId)) {
                    networkIdleFuture.complete(null);
                }
                break;
            default:
                logger.log(FINE, "Unhandled event {0}", event);
                break;
        }
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            browser.call("Target.closeTarget", new JSONObject().put("targetId", targetId));
            browser.sessionEventHandlers.remove(sessionId);
        }
    }

    public void interceptRequests(Consumer<ChromiumRequest> requestHandler) {
        this.requestInterceptor = requestHandler;
        call("Fetch.enable", new JSONObject());
    }

    public CompletableFuture<Void> navigate(String url) {
        if (loadFuture != null) {
            loadFuture.completeExceptionally(new InterruptedIOException("navigated away"));
        }
        loadFuture = new CompletableFuture<>();
        networkIdleFuture = new CompletableFuture<>();
        call("Page.navigate", new JSONObject().put("url", url));
        return CompletableFuture.allOf(networkIdleFuture, loadFuture);
    }

    public byte[] screenshot() {
        return Base64.getDecoder().decode(call("Page.captureScreenshot", new JSONObject().put("format", "jpeg")).getString("data"));
    }

    private JSONObject eval(String expression) {
        return call("Runtime.evaluate", new JSONObject().put("expression", expression).put("returnByValue", true)).getJSONObject("result");
    }

    @SuppressWarnings("unchecked")
    public List<String> extractLinks() {
        List<String> links = new ArrayList<>();
        JSONArray array = eval("Array.from(document.querySelectorAll('a[href], area[href]')).map(link => link.protocol+'//'+link.host+link.pathname+link.search+link.hash)").getJSONArray("value");
        for (int i = 0; i < array.length(); i++) {
            links.add(array.getString(i));
        }
        return links;
    }

    public String title() {
        return eval("document.title").getString("value");
    }
}
