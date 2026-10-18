package com.tingting.gemini9420;

import java.net.URI;

final class GeminiConfig {
    final String apiKey;
    final String model;
    final String systemInstruction;
    final String apiHost;
    final String tcpHost;
    final int tcpPort;

    GeminiConfig(String apiKey, String model, String systemInstruction,
                 String apiHost, String tcpHost, int tcpPort) {
        this.apiKey = apiKey;
        this.model = model;
        this.systemInstruction = systemInstruction;
        this.apiHost = apiHost;
        this.tcpHost = tcpHost;
        this.tcpPort = tcpPort;
    }

    static GeminiConfig from(PropertiesReader p) {
        String key = firstNonEmpty(p.get("api_key"), p.get("gemini_key"));
        String model = p.get("model", "gemini-2.5-flash-native-audio-preview-09-2025");
        String sys = p.get("system_instruction",
                "你是腾讯听听智能音箱上的中文语音助手，回答简短自然，适合口语播报。");
        String apiHost = parseHost(p.get("gemini_api_url", "https://generativelanguage.googleapis.com"),
                "generativelanguage.googleapis.com");
        String relay = p.get("tcp_relay_host", "").trim();
        int relayPort = parseInt(p.get("tcp_relay_port", "18443"), 18443);
        String tcpHost = relay.length() > 0 ? relay : apiHost;
        int tcpPort = relay.length() > 0 ? relayPort : 443;
        return new GeminiConfig(key, model, sys, apiHost, tcpHost, tcpPort);
    }

    private static String firstNonEmpty(String a, String b) {
        if (a != null && a.trim().length() > 0) return a.trim();
        if (b != null) return b.trim();
        return "";
    }

    private static String parseHost(String url, String def) {
        try {
            if (url == null || url.trim().length() == 0) return def;
            String u = url.trim();
            if (!u.contains("://")) u = "https://" + u;
            return new URI(u).getHost();
        } catch (Exception e) {
            return def;
        }
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    interface PropertiesReader {
        String get(String key);

        String get(String key, String def);
    }
}
