package org.archive.browser;

import org.json.JSONObject;

import java.util.*;

public class ChromiumRequest {
    final String resourceType;
    private final ChromiumTab tab;
    private final String id;
    private final JSONObject request;
    boolean handled = false;

    ChromiumRequest(ChromiumTab tab, String id, JSONObject request, String resourceType) {
        this.tab = tab;
        this.id = id;
        this.request = request;
        this.resourceType = resourceType;
    }

    public String url() {
        return request.getString("url");
    }

    public String method() {
        return request.getString("method");
    }

    public Map<String, String> headers() {
        Map<String, String> map = new HashMap<>();
        JSONObject headers = request.getJSONObject("headers");
        for (Object key : headers.keySet()) {
            if (key instanceof String) {
                map.put((String) key, headers.getString((String) key));
            }
        }
        return Collections.unmodifiableMap(map);
    }

    public void fulfill(int status, String reason, Collection<Map.Entry<String, String>> headers, byte[] body) {
        enforceHandledOnce();
        List<Map<String, String>> headerList = new ArrayList<>();
        for (Map.Entry<String, String> entry : headers) {
            Map<String, String> map = new HashMap<>();
            map.put("name", entry.getKey());
            map.put("value", entry.getValue());
            headerList.add(map);
        }
        JSONObject params = new JSONObject();
        params.put("requestId", id);
        params.put("responseCode", status);
        if (!reason.equals("")) params.put("responsePhrase", reason);
        params.put("responseHeaders", headerList);
        params.put("body", Base64.getEncoder().encodeToString(body));
        tab.call("Fetch.fulfillRequest", params);
    }

    public void continueNormally() {
        enforceHandledOnce();
        tab.call("Fetch.continueRequest", new JSONObject().put("requestId", id));
    }

    public void fail(String errorReason) {
        enforceHandledOnce();
        tab.call("Fetch.failRequest", new JSONObject().put("requestId", id).put("errorReason", errorReason));
    }

    private void enforceHandledOnce() {
        if (handled) {
            throw new IllegalStateException("Request already handled");
        } else {
            handled = true;
        }
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{" +
                "url='" + url() + '\'' +
                ", method='" + method() + '\'' +
                ", headers=" + headers() +
                '}';
    }

}
