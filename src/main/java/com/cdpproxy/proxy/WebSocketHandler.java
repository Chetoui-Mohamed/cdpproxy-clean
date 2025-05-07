package com.cdpproxy.proxy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import com.cdpproxy.util.LocatorDetector;
import com.cdpproxy.util.LocatorVerificationTracker;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import com.cdpproxy.util.CDPMessageDumper;

@Component
public class WebSocketHandler extends TextWebSocketHandler {
    private static final Logger logger = Logger.getLogger(WebSocketHandler.class.getName());
    private final Map<String, BrowserWebSocketClient> browserConnections = new HashMap<>();
    private final Map<String, Queue<String>> pendingMessages = new HashMap<>();
    private final Map<String, Map<Integer, String>> sessionPendingSelectors = new ConcurrentHashMap<>();

    @Value("${cdp.browser.http.url:http://localhost:9222}")
    private String browserHttpUrl;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        logger.info("New connection from Playwright client: " + session.getId());
        pendingMessages.put(session.getId(), new ConcurrentLinkedQueue<>());
        sessionPendingSelectors.put(session.getId(), new ConcurrentHashMap<>());
        if (sessionPendingSelectors.size() == 1) {
            initLocatorDetection();
        }
    }

    private String sanitizeMessage(String message) {
        try {
            JSONObject json = new JSONObject(message);
            JSONObject sanitized = new JSONObject();

            if (json.has("id")) {
                sanitized.put("id", json.get("id"));
            }
            if (json.has("method")) {
                sanitized.put("method", json.getString("method"));
            }
            if (json.has("params")) {
                sanitized.put("params", json.getJSONObject("params"));
            }
            if (json.has("sessionId") && json.get("sessionId") instanceof String) {
                sanitized.put("sessionId", json.getString("sessionId"));
            }

            return sanitized.toString();
        } catch (Exception e) {
            logger.warning("Failed to sanitize message, using original: " + e.getMessage());
            return message;
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();
        CDPMessageDumper.dumpMessage("FROM_PLAYWRIGHT", payload);
        String sanitizedPayload = sanitizeMessage(payload);

        try {
            JSONObject json = new JSONObject(payload);

            // Track CSS selectors
            if (json.has("id") && json.has("method") && json.getString("method").equals("DOM.querySelector")) {
                int id = json.getInt("id");
                String selector = json.getJSONObject("params").getString("selector");
                Map<Integer, String> pendingSelectors = sessionPendingSelectors.computeIfAbsent(
                        session.getId(), k -> new ConcurrentHashMap<>());
                pendingSelectors.put(id, selector);
                LocatorVerificationTracker.trackLocator(session.getId(), selector, "CSS");
            }
            // Enhanced detection for all types of locator operations
            else if (json.has("method") && json.has("params")) {
                LocatorDetector.SelectorInfo selectorInfo = LocatorDetector.extractSelector(json);
                if (selectorInfo != null) {
                    LocatorVerificationTracker.trackLocator(
                            session.getId(), selectorInfo.selector, selectorInfo.type);
                }
            }
        } catch (Exception e) {
            logger.warning("Failed to analyze message for locator tracking: " + e.getMessage());
        }

        // Handle connection to browser
        BrowserWebSocketClient browserClient = browserConnections.get(session.getId());

        if (browserClient == null || !browserClient.isConnected()) {
            // Queue message until connection established
            Queue<String> queue = pendingMessages.get(session.getId());
            if (queue != null) {
                queue.add(sanitizedPayload);
            }

            if (browserClient == null) {
                try {
                    String browserWsUrl = discoverBrowserWebSocketUrl();
                    Map<Integer, String> pendingSelectors = sessionPendingSelectors.computeIfAbsent(
                            session.getId(), k -> new ConcurrentHashMap<>());
                    final BrowserWebSocketClient newBrowserClient = new BrowserWebSocketClient(
                            new URI(browserWsUrl), session, pendingSelectors);
                    browserConnections.put(session.getId(), newBrowserClient);
                    newBrowserClient.connect();

                    new Thread(() -> {
                        try {
                            if (newBrowserClient.waitForConnection(10, TimeUnit.SECONDS)) {
                                processPendingMessages(session.getId());
                            } else {
                                try {
                                    JSONObject errorMsg = new JSONObject();
                                    errorMsg.put("error", "Timed out waiting for browser connection");
                                    session.sendMessage(new TextMessage(errorMsg.toString()));
                                } catch (IOException ex) {
                                    logger.severe("Failed to send error to client: " + ex.getMessage());
                                }
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }).start();
                } catch (Exception e) {
                    logger.severe("Failed to connect to browser: " + e.getMessage());
                    try {
                        JSONObject errorMsg = new JSONObject();
                        errorMsg.put("error", "Failed to connect to browser: " + e.getMessage());
                        session.sendMessage(new TextMessage(errorMsg.toString()));
                    } catch (IOException ex) {
                        logger.severe("Failed to send error to client: " + ex.getMessage());
                    }
                }
            }
        } else {
            // Connection is already established, send the message right away
            try {
                browserClient.send(sanitizedPayload);
            } catch (Exception e) {
                logger.severe("Failed to send message to browser: " + e.getMessage());
                Queue<String> queue = pendingMessages.get(session.getId());
                if (queue != null) {
                    queue.add(sanitizedPayload);
                }

                try {
                    browserClient.reconnect();
                } catch (Exception ex) {
                    logger.severe("Failed to reconnect to browser: " + ex.getMessage());
                }
            }
        }
    }

    private void processPendingMessages(String sessionId) {
        Queue<String> queue = pendingMessages.get(sessionId);
        BrowserWebSocketClient browserClient = browserConnections.get(sessionId);

        if (queue == null || browserClient == null) {
            return;
        }

        String message;
        while ((message = queue.poll()) != null) {
            try {
                browserClient.send(message);
            } catch (Exception e) {
                logger.severe("Failed to send queued message: " + e.getMessage());
                queue.add(message);
                break;
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        logger.info("Connection closed from Playwright client: " + session.getId());
        pendingMessages.remove(session.getId());
        sessionPendingSelectors.remove(session.getId());

        BrowserWebSocketClient browserClient = browserConnections.remove(session.getId());
        if (browserClient != null) {
            browserClient.close();
        }
    }

    private String discoverBrowserWebSocketUrl() throws Exception {
        // Try /json/version endpoint first
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(browserHttpUrl + "/json/version"))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JSONObject json = new JSONObject(response.body());
                if (json.has("webSocketDebuggerUrl")) {
                    return json.getString("webSocketDebuggerUrl");
                }
            }
        } catch (Exception e) {
            logger.warning("Failed to get browser WebSocket URL from /json/version: " + e.getMessage());
        }

        // Fall back to targets list
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(browserHttpUrl + "/json/list"))
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new Exception("Failed to get browser targets. Status code: " + response.statusCode());
        }

        JSONArray targets = new JSONArray(response.body());

        if (targets.length() == 0) {
            throw new Exception("No debugging targets found in Chrome");
        }

        // Try to find a page target first
        for (int i = 0; i < targets.length(); i++) {
            JSONObject target = targets.getJSONObject(i);
            if ("page".equals(target.optString("type")) && target.has("webSocketDebuggerUrl")) {
                return target.getString("webSocketDebuggerUrl");
            }
        }

        // If no page target, take the first available
        for (int i = 0; i < targets.length(); i++) {
            JSONObject target = targets.getJSONObject(i);
            if (target.has("webSocketDebuggerUrl")) {
                return target.getString("webSocketDebuggerUrl");
            }
        }

        throw new Exception("No valid WebSocket URL found in Chrome debugging targets");
    }

    private static class BrowserWebSocketClient extends WebSocketClient {
        private final WebSocketSession playwrightSession;
        private volatile boolean isConnected = false;
        private final CountDownLatch connectionLatch = new CountDownLatch(1);
        private final Map<Integer, String> pendingSelectors;

        public BrowserWebSocketClient(URI serverUri, WebSocketSession playwrightSession, Map<Integer, String> pendingSelectors) {
            super(serverUri);
            this.playwrightSession = playwrightSession;
            this.pendingSelectors = pendingSelectors;
            this.setConnectionLostTimeout(30000);
        }

        @Override
        public void onOpen(ServerHandshake handshakedata) {
            logger.info("Connected to browser WebSocket");
            isConnected = true;
            connectionLatch.countDown();
        }

        @Override
        public void onMessage(String message) {
            CDPMessageDumper.dumpMessage("FROM_BROWSER", message);

            try {
                JSONObject json = new JSONObject(message);

                // Process locator verification
                verifyLocatorsFromResponse(json);

                // Check for broken locators in responses
                checkForBrokenLocators(json);

                // Forward the message back to Playwright
                if (playwrightSession.isOpen()) {
                    playwrightSession.sendMessage(new TextMessage(message));
                }
            } catch (Exception e) {
                logger.warning("Failed to process browser message: " + e.getMessage());
            }
        }

        private void verifyLocatorsFromResponse(JSONObject json) {
            try {
                // Check for strict mode violations (working but multiple matches)
                if (json.has("result") && json.getJSONObject("result").has("result")) {
                    JSONObject result = json.getJSONObject("result");

                    // Check for exception details with strict mode violation
                    if (result.has("exceptionDetails") &&
                            result.getJSONObject("exceptionDetails").has("exception")) {

                        JSONObject exception = result.getJSONObject("exceptionDetails").getJSONObject("exception");

                        if (exception.has("description") && exception.get("description") instanceof String) {
                            String errorDesc = exception.getString("description");

                            if (errorDesc.contains("strict mode violation") && errorDesc.contains("resolved to")) {
                                int startIdx = errorDesc.indexOf("locator(") + 9;
                                int endIdx = errorDesc.indexOf(")", startIdx);

                                if (startIdx > 9 && endIdx > startIdx) {
                                    String selectorWithQuotes = errorDesc.substring(startIdx, endIdx);
                                    String selector = selectorWithQuotes.substring(1, selectorWithQuotes.length() - 1);
                                    LocatorVerificationTracker.verifyLocator(playwrightSession.getId(), selector);
                                }
                            }
                        }
                    }

                    // Check for successful locator with visible and attached properties
                    if (json.has("id") && json.getJSONObject("result").has("result")) {
                        int id = json.getInt("id");
                        result = json.getJSONObject("result").getJSONObject("result");

                        if (result.has("type") && result.getString("type").equals("object") &&
                                result.has("value") && result.get("value") instanceof JSONObject) {

                            JSONObject value = result.getJSONObject("value");

                            if (value.has("o") && value.get("o") instanceof JSONArray) {
                                JSONArray properties = value.getJSONArray("o");

                                boolean isVisible = false;
                                boolean isAttached = false;
                                int elementCount = 0;
                                String logText = "";

                                for (int i = 0; i < properties.length(); i++) {
                                    JSONObject property = properties.getJSONObject(i);

                                    if (property.has("k") && property.has("v")) {
                                        String key = property.getString("k");

                                        if (key.equals("visible") && property.get("v") instanceof Boolean) {
                                            isVisible = property.getBoolean("v");
                                        }

                                        if (key.equals("attached") && property.get("v") instanceof Boolean) {
                                            isAttached = property.getBoolean("v");
                                        }

                                        if (key.equals("log") && property.get("v") instanceof String) {
                                            logText = property.getString("v");

                                            if (logText.contains("resolved to ")) {
                                                try {
                                                    int startIndex = logText.indexOf("resolved to ") + 12;
                                                    int endIndex = logText.indexOf(" element", startIndex);
                                                    if (endIndex > startIndex) {
                                                        String countStr = logText.substring(startIndex, endIndex).trim();
                                                        elementCount = Integer.parseInt(countStr);
                                                    }
                                                } catch (Exception ignored) {}
                                            }
                                        }
                                    }
                                }

                                if ((isVisible && isAttached) || elementCount > 0 || logText.contains("resolved to")) {
                                    String selector = findSelectorForId(id);
                                    if (selector != null) {
                                        LocatorVerificationTracker.verifyLocator(playwrightSession.getId(), selector);
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                logger.warning("Error verifying locators: " + e.getMessage());
            }
        }

        private void checkForBrokenLocators(JSONObject json) {
            try {
                if (json.has("id")) {
                    int id = json.getInt("id");

                    // Remove pendingSelectors entry if it exists
                    if (pendingSelectors.containsKey(id)) {
                        pendingSelectors.remove(id);
                    }

                    // Check for success=false in result
                    if (json.has("result") && json.getJSONObject("result").has("result")) {
                        JSONObject result = json.getJSONObject("result").getJSONObject("result");

                        if (result.has("type") && result.getString("type").equals("object") &&
                                result.has("value") && result.get("value") instanceof JSONObject) {

                            JSONObject value = result.getJSONObject("value");

                            if (value.has("o") && value.get("o") instanceof JSONArray) {
                                JSONArray properties = value.getJSONArray("o");

                                boolean foundSuccessProperty = false;
                                boolean successValue = true;

                                for (int i = 0; i < properties.length(); i++) {
                                    JSONObject property = properties.getJSONObject(i);

                                    if (property.has("k") && property.getString("k").equals("success")) {
                                        foundSuccessProperty = true;
                                        if (property.has("v") && property.get("v") instanceof Boolean) {
                                            successValue = property.getBoolean("v");
                                        }
                                        break;
                                    }
                                }

                                if (foundSuccessProperty && !successValue) {
                                    String selector = findSelectorForId(id);
                                    if (selector == null) selector = "unknown";

                                    // LoggingUtil call removed
                                    logger.warning("Broken locator detected: " + selector + " (success=false)");
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                logger.warning("Error checking for broken locators: " + e.getMessage());
            }
        }

        private String findSelectorForId(int id) {
            if (pendingSelectors.containsKey(id)) {
                return pendingSelectors.get(id);
            }
            return null;
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            logger.info("Browser WebSocket connection closed: " + code + " - " + reason);
            isConnected = false;
        }

        @Override
        public void onError(Exception ex) {
            logger.severe("Error in browser WebSocket connection: " + ex.getMessage());
        }

        public boolean waitForConnection(long timeout, TimeUnit unit) throws InterruptedException {
            return connectionLatch.await(timeout, unit);
        }

        public boolean isConnected() {
            return isConnected && isOpen();
        }

        @Override
        public void send(String text) {
            if (isConnected() && isOpen()) {
                super.send(text);
            } else {
                throw new RuntimeException("Cannot send message because WebSocket is not connected");
            }
        }
    }

    private void initLocatorDetection() {
        try {
            LocatorVerificationTracker.initialize();
            logger.info("Locator detection system initialized");
        } catch (Exception e) {
            logger.severe("Failed to initialize locator detection: " + e.getMessage());
        }
    }
}