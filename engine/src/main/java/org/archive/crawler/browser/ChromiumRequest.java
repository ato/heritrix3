package org.archive.crawler.browser;

import org.apache.commons.httpclient.Header;
import org.apache.commons.lang.StringUtils;
import org.json.JSONArray;
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

    public String getUrl() {
        return request.getString("url");
    }

    public String getMethod() {
        return request.getString("method");
    }

    public Map<String, String> getHeaders() {
        Map<String, String> map = new HashMap<>();
        JSONObject headers = request.getJSONObject("headers");
        for (Object key : headers.keySet()) {
            if (key instanceof String) {
                map.put((String) key, headers.getString((String) key));
            }
        }
        return Collections.unmodifiableMap(map);
    }

    public void fulfill(int status, String reason, Header[] headers, byte[] body) {
        JSONArray headerArray = new JSONArray();
        for (Header header: headers) {
            headerArray.put(new JSONObject().put("name", header.getName()).put("value", header.getValue()));
        }
        fulfill(status, reason, headerArray, body);
    }

    public void fulfill(int status, String reason, Map<String, String> headers, byte[] body) {
        JSONArray headerArray = new JSONArray();
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                headerArray.put(new JSONObject().put("name", entry.getKey()).put("value", entry.getValue()));
            }
        }
        fulfill(status, reason, headerArray, body);
    }

    private void fulfill(int status, String reason, JSONArray headerArray, byte[] body) {
        enforceHandledOnce();
        JSONObject params = new JSONObject();
        params.put("requestId", id);
        params.put("responseCode", status);
        if (!StringUtils.isEmpty(reason)) params.put("responsePhrase", reason);
        params.put("responseHeaders", headerArray);
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

    public void fail(int fetchStatus) {
        fail(mapStatusToChromeErrorReason(fetchStatus));
    }

    private String mapStatusToChromeErrorReason(int fetchStatus) {
        switch (fetchStatus) {
            case -1:
            case -6:
            case -7:
                return "NameNotResolved";
            case -2:
                return "ConnectionRefused";
            case -3:
                return "ConnectionReset";
            case -4:
                return "TimedOut";
            case -61:
            case -62:
            case -63:
            case -4001:
            case -4002:
            case -5000:
            case -5001:
            case -5002:
            case -5003:
            case -5004:
            case -9998:
                return "BlockedByClient";
            case -6000:
            case -7000:
                return "ConnectionAborted";
            default:
                return "Failed";
        }
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
                "url='" + getUrl() + '\'' +
                ", method='" + getMethod() + '\'' +
                ", headers=" + getHeaders() +
                '}';
    }

}
