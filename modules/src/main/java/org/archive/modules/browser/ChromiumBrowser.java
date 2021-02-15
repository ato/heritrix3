package org.archive.modules.browser;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;

import java.io.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.logging.Logger;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.util.logging.Level.*;

public class ChromiumBrowser implements Closeable {
    private static final Logger logger = Logger.getLogger(ChromiumBrowser.class.getName());
    private static final List<String> executables = Arrays.asList("chromium-browser", "chromium", "google-chrome",
            "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe");
    private static final Set<Process> allProcesses = Collections.newSetFromMap(new ConcurrentHashMap<>());

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            for (Process child : allProcesses) {
                child.destroy();
            }
            for (Process child : allProcesses) {
                try {
                    child.waitFor(1, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    // whatever
                }
                child.destroyForcibly();
            }
        }, "Browser shutdown hook"));
    }

    private final Process process;
    private final WebSocket websocket;
    private final AtomicLong idSeq = new AtomicLong(0);
    final ConcurrentHashMap<String, Consumer<JSONObject>> sessionEventHandlers = new ConcurrentHashMap<>();
    private final Map<Long, CompletableFuture<JSONObject>> calls = new ConcurrentHashMap<>();
    private final ScheduledThreadPoolExecutor scheduledExecutor = new ScheduledThreadPoolExecutor(1);

    public ChromiumBrowser() throws IOException {
        scheduledExecutor.setRemoveOnCancelPolicy(true);
        Process process = null;
        for (String executable : executables) {
            try {
                List<String> cmd = new ArrayList<>();
                cmd.add(executable);
                cmd.add("--headless");
                cmd.add("--remote-debugging-port=0");
                process = new ProcessBuilder(cmd)
                        .inheritIO()
                        .redirectError(ProcessBuilder.Redirect.PIPE)
                        .start();
            } catch (IOException e) {
                continue;
            }
            break;
        }
        if (process == null) throw new IOException("Couldn't execute any of: " + executables);
        allProcesses.add(process);
        this.process = process;
        String url = readDevtoolsUrlFromStderr();
        logger.fine("Connecting to " + url);
        websocket = new WebSocket(URI.create(url));
        try {
            websocket.connectBlocking();
        } catch (InterruptedException e) {
            throw new IOException("Connect interrupted", e);
        }
    }

    public boolean alive() {
        try {
            call("Browser.getVersion", new JSONObject());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    JSONObject call(String method, JSONObject params) {
        return call(null, method, params);
    }

    JSONObject call(String sessionId, String method, JSONObject params) {
        long id = idSeq.incrementAndGet();
        JSONObject message = new JSONObject();
        message.put("id", id);
        if (sessionId != null) {
            message.put("sessionId", sessionId);
        }
        message.put("method", method);
        message.put("params", params);
        String messageString = message.toString();
        logger.log(FINEST, "> {0} {1}", new Object[]{messageString.length(), messageString.length() < 1024 ? messageString : messageString.substring(0, 1024) + "..."});
        CompletableFuture<JSONObject> future = new CompletableFuture<>();
        calls.put(id, future);
        websocket.send(messageString);
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } catch (TimeoutException e) {
            throw new RuntimeException("Call timed out: " + message, e);
        } catch (ExecutionException e) {
            throw new ChromiumException(method + ": " + e.getCause().getMessage(), e.getCause());
        }
    }

    private String readDevtoolsUrlFromStderr() throws IOException {
        String devtoolsUrl;
        BufferedReader rdr = new BufferedReader(new InputStreamReader(process.getErrorStream(), ISO_8859_1));
        CompletableFuture<String> future = new CompletableFuture<>();
        Thread thread = new Thread(() -> {
            String listenMsg = "DevTools listening on ";
            try {
                while (true) {
                    String line = rdr.readLine();
                    if (line == null) break;
                    if (!future.isDone() && line.startsWith(listenMsg)) {
                        future.complete(line.substring(listenMsg.length()));
                    }
                    logger.log(FINER, "Chromium STDERR: {0}", line);
                }
            } catch (IOException e) {
                logger.log(WARNING, "Error reading Chrome stderr", e);
                future.completeExceptionally(e);
            }
        });
        thread.setName("Chrome stderr");
        thread.start();

        try {
            devtoolsUrl = future.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            if (e.getCause() instanceof IOException) {
                throw (IOException) e.getCause();
            } else {
                throw new IOException(e);
            }
        }
        return devtoolsUrl;
    }

    public void close() {
        scheduledExecutor.shutdown();
        try {
            websocket.close();
        } catch (Exception e) {
            // ignore
        }
        process.destroy();
        try {
            process.waitFor(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            // uh
        } finally {
            process.destroyForcibly();
            allProcesses.remove(process);
        }
    }

    public ChromiumTab createTab() {
        return new ChromiumTab(this);
    }

    private class WebSocket extends WebSocketClient {
        public WebSocket(URI uri) {
            super(uri);
            setConnectionLostTimeout(-1); // Chrome doesn't respond to WebSocket pings
        }

        @Override
        public void onOpen(ServerHandshake serverHandshake) {
            logger.finer("Connected to chromium");
        }

        @Override
        public void onMessage(String rawMessage) {
            logger.log(FINEST, "< {0}", (rawMessage.length() > 1024 ? rawMessage.substring(0, 1024) + "..." : rawMessage));
            try {
                JSONObject message = new JSONObject(rawMessage);
                if (message.has("method")) {
                    if (message.has("sessionId")) {
                        Consumer<JSONObject> handler = sessionEventHandlers.get(message.getString("sessionId"));
                        if (handler != null) {
                            ForkJoinPool.commonPool().submit(() -> {
                                try {
                                    handler.accept(message);
                                } catch (Throwable t) {
                                    logger.log(WARNING, "Exception handling browser event " + rawMessage, t);
                                }
                            });
                        } else {
                            logger.log(WARNING, "Event for unknown session " + rawMessage);
                        }
                    }
                } else {
                    long id = message.getLong("id");
                    CompletableFuture<JSONObject> future = calls.remove(id);
                    if (future == null) {
                        logger.warning("Unexpected RPC response id " + id);
                    } else if (message.has("error")) {
                        future.completeExceptionally(new ChromiumException(message.getJSONObject("error").getString("message")));
                    } else {
                        future.complete(message.getJSONObject("result"));
                    }
                }
            } catch (Throwable e) {
                logger.log(WARNING, "Exception handling message from Chromium", e);
                throw e;
            }
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            if (remote) {
                logger.log(WARNING, "Chromium unexpectedly closed websocket: {0}: {1}", new Object[]{code, reason});
            }
        }

        @Override
        public void onError(Exception ex) {
            logger.log(WARNING, "Chromium websocket error", ex);
        }
    }
}