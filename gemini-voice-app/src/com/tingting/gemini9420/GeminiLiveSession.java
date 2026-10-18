package com.tingting.gemini9420;

import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

final class GeminiLiveSession {
    private static final String TAG = "GeminiLive";
    private static final int CONNECT_MS = 25000;

    interface Listener {
        void onSetupComplete();

        void onAudioOut(byte[] pcm24);

        void onInterrupted();

        void onTurnComplete();

        void onClosed(String reason);

        void onError(String msg);
    }

    private final GeminiConfig cfg;
    private final Listener listener;
    private SimpleWebSocket ws;
    private volatile boolean open;

    GeminiLiveSession(GeminiConfig cfg, Listener listener) {
        this.cfg = cfg;
        this.listener = listener;
    }

    void start() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                runSession();
            }
        }, "gemini-live");
        t.start();
    }

    void sendPcm16k(byte[] pcm) {
        SimpleWebSocket s = ws;
        if (!open || s == null || pcm == null || pcm.length == 0) return;
        try {
            String b64 = Base64.encodeToString(pcm, Base64.NO_WRAP);
            JSONObject audio = new JSONObject();
            audio.put("data", b64);
            audio.put("mimeType", "audio/pcm;rate=16000");
            JSONObject realtime = new JSONObject();
            realtime.put("audio", audio);
            JSONObject root = new JSONObject();
            root.put("realtimeInput", realtime);
            s.sendText(root.toString());
        } catch (Exception e) {
            Log.e(TAG, "send pcm", e);
            listener.onError(e.getMessage());
        }
    }

    void sendUserText(String text) {
        SimpleWebSocket s = ws;
        if (!open || s == null || text == null || text.length() == 0) return;
        try {
            JSONObject part = new JSONObject().put("text", text);
            JSONObject turn = new JSONObject()
                    .put("role", "user")
                    .put("parts", new JSONArray().put(part));
            JSONObject cc = new JSONObject()
                    .put("turns", new JSONArray().put(turn))
                    .put("turnComplete", true);
            JSONObject root = new JSONObject().put("clientContent", cc);
            s.sendText(root.toString());
            Log.i(TAG, "sent user text turn");
        } catch (Exception e) {
            Log.e(TAG, "send text", e);
        }
    }

    void close() {
        open = false;
        if (ws != null) ws.close();
    }

    private void runSession() {
        try {
            String path = "/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
                    + "?key=" + cfg.apiKey;
            Log.i(TAG, "connect tls=" + cfg.apiHost + " tcp=" + cfg.tcpHost + ":" + cfg.tcpPort);
            ws = new SimpleWebSocket(cfg.tcpHost, cfg.tcpPort, cfg.apiHost, path, CONNECT_MS);
            ws.connect();
            Log.i(TAG, "websocket open");
            open = true;
            sendSetup();
            readLoop();
        } catch (Exception e) {
            Log.e(TAG, "connect", e);
            listener.onError(String.valueOf(e.getMessage()));
            listener.onClosed("connect");
        }
    }

    private void sendSetup() throws Exception {
        JSONObject gen = new JSONObject();
        gen.put("responseModalities", new JSONArray().put("AUDIO"));
        JSONObject setupInner = new JSONObject();
        setupInner.put("model", cfg.model.startsWith("models/") ? cfg.model : ("models/" + cfg.model));
        setupInner.put("generationConfig", gen);
        if (cfg.systemInstruction != null && cfg.systemInstruction.length() > 0) {
            JSONObject sys = new JSONObject();
            sys.put("parts", new JSONArray().put(new JSONObject().put("text", cfg.systemInstruction)));
            setupInner.put("systemInstruction", sys);
        }
        JSONObject setup = new JSONObject();
        setup.put("setup", setupInner);
        ws.sendText(setup.toString());
    }

    private void readLoop() {
        try {
            while (open) {
                String msg = ws.receiveText();
                if (msg == null) break;
                if (msg.length() < 400) Log.d(TAG, "rx " + msg);
                handleMessage(msg);
            }
        } catch (java.net.SocketTimeoutException e) {
            if (open) readLoop();
            return;
        } catch (Exception e) {
            if (open) {
                Log.e(TAG, "read", e);
                listener.onError(e.getMessage());
            }
        } finally {
            open = false;
            listener.onClosed("eof");
        }
    }

    private void handleMessage(String msg) {
        try {
            JSONObject root = new JSONObject(msg);
            if (root.has("error")) {
                Log.e(TAG, "server error " + root.getJSONObject("error"));
                listener.onError(root.getJSONObject("error").optString("message", msg));
                return;
            }
            if (root.has("setupComplete")) {
                listener.onSetupComplete();
                return;
            }
            if (root.has("serverContent")) {
                JSONObject sc = root.getJSONObject("serverContent");
                if (sc.optBoolean("interrupted", false)) {
                    listener.onInterrupted();
                }
                if (sc.has("modelTurn")) {
                    JSONObject turn = sc.getJSONObject("modelTurn");
                    JSONArray parts = turn.optJSONArray("parts");
                    if (parts != null) {
                        for (int i = 0; i < parts.length(); i++) {
                            JSONObject part = parts.getJSONObject(i);
                            if (!part.has("inlineData")) continue;
                            JSONObject inline = part.getJSONObject("inlineData");
                            String mime = inline.optString("mimeType", "");
                            if (!mime.startsWith("audio/pcm")) continue;
                            byte[] pcm = Base64.decode(inline.getString("data"), Base64.DEFAULT);
                            if (pcm.length > 0) {
                                Log.d(TAG, "audio out " + pcm.length + " mime=" + mime);
                                listener.onAudioOut(pcm);
                            }
                        }
                    }
                }
                if (sc.optBoolean("turnComplete", false) || sc.optBoolean("generationComplete", false)) {
                    listener.onTurnComplete();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "parse " + e + " raw=" + (msg.length() > 300 ? msg.substring(0, 300) : msg));
        }
    }
}
