package com.cdpproxy.controller;

import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DevToolsProxyController {

    @Value("${server.port}")
    private int serverPort;

    // Version sans barre oblique à la fin
    @GetMapping("/json/version")
    public String getVersion() {
        return createVersionResponse();
    }

    // Version avec barre oblique à la fin
    @GetMapping("/json/version/")
    public String getVersionWithSlash() {
        return createVersionResponse();
    }

    private String createVersionResponse() {
        JSONObject version = new JSONObject();
        version.put("webSocketDebuggerUrl", "ws://localhost:" + serverPort + "/cdp");
        version.put("Browser", "Chrome/115.0.0.0");
        version.put("Protocol-Version", "1.3");
        version.put("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
        version.put("V8-Version", "11.5.0");
        version.put("WebKit-Version", "537.36");
        return version.toString();
    }

    // Version sans barre oblique à la fin
    @GetMapping("/json/list")
    public String getList() {
        return createListResponse();
    }

    // Version avec barre oblique à la fin
    @GetMapping("/json/list/")
    public String getListWithSlash() {
        return createListResponse();
    }

    private String createListResponse() {
        JSONArray targets = new JSONArray();
        JSONObject target = new JSONObject();
        target.put("description", "");
        target.put("devtoolsFrontendUrl", "");
        target.put("id", "default");
        target.put("title", "CDP Proxy");
        target.put("type", "page");
        target.put("url", "about:blank");
        target.put("webSocketDebuggerUrl", "ws://localhost:" + serverPort + "/cdp");
        targets.put(target);
        return targets.toString();
    }
}