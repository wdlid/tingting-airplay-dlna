package com.tingting.gemini9420;

import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.util.Properties;

/**
 * 9420 唤醒后 Gemini Live 全双工会话 (独立 APK, 不触碰 AirPlay/DLNA)。
 */
public final class VoiceAgentController {
    private static final String TAG = "VoiceAgent";
    static final String ACTION_WAKE = "com.tingting.gemini9420.action.WAKE_9420";
    static final String ACTION_SESSION_END = "com.tingting.gemini9420.action.SESSION_END";
    static final String WAKE_STAMP = "/data/local/tmp/tingting_wake";
    static final String VOICE_BUSY = "/data/local/tmp/tingting_voice_busy";

    private final Context ctx;
    private final Object lock = new Object();
    private volatile boolean sessionActive;
    private volatile boolean endConversation;
    private volatile boolean geminiReady;
    private volatile long conversationGeneration;
    private GeminiLiveSession session;
    private AudioRecord recorder;
    private VoicePcmPlayer pcmPlayer;
    private Thread micThread;
    private long lastStamp;
    private long lastWakeMs;
    private long sessionStartMs;
    private volatile int turnsPlayed;
    private Thread stampPoller;

    public VoiceAgentController(Context ctx) {
        this.ctx = ctx.getApplicationContext();
    }

    void startMonitoring() {
        if (stampPoller != null) return;
        File st = new File(WAKE_STAMP);
        if (st.exists()) lastStamp = st.lastModified();
        stampPoller = new Thread(new Runnable() {
            @Override
            public void run() {
                pollWakeStamp();
            }
        }, "wake-stamp");
        stampPoller.setDaemon(true);
        stampPoller.start();
    }

    void onWake9420() {
        long now = System.currentTimeMillis();
        if (now - lastWakeMs < 12000) {
            Log.i(TAG, "onWake9420 debounced");
            return;
        }
        synchronized (lock) {
            if (sessionActive) {
                long stuckMs = now - sessionStartMs;
                if (stuckMs < 20000) {
                    Log.w(TAG, "wake while busy, ignore stuckMs=" + stuckMs);
                    return;
                }
                Log.w(TAG, "force reset stuck session stuckMs=" + stuckMs);
                forceResetSessionLocked();
            }
        }
        lastWakeMs = now;
        Log.i(TAG, "onWake9420");
        final long gen;
        synchronized (lock) {
            conversationGeneration++;
            gen = conversationGeneration;
            sessionActive = true;
            endConversation = false;
            geminiReady = false;
            sessionStartMs = now;
            turnsPlayed = 0;
        }
        final long runGen = gen;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                runConversation(runGen);
            }
        }, "voice-session");
        t.start();
    }

    private void runConversation(final long gen) {
        Log.i(TAG, "runConversation begin gen=" + gen);
        final Properties raw = loadConfig();
        final GeminiConfig cfg = GeminiConfig.from(new GeminiConfig.PropertiesReader() {
            public String get(String key) {
                return raw.getProperty(key);
            }

            public String get(String key, String def) {
                return raw.getProperty(key, def);
            }
        });
        if (cfg.apiKey.length() == 0 || cfg.apiKey.startsWith("YOUR_")) {
            Log.e(TAG, "missing gemini_key in files/gemini.properties");
            endSession(gen);
            return;
        }
        Log.i(TAG, "config ok host=" + cfg.apiHost + " tcp=" + cfg.tcpHost + ":" + cfg.tcpPort);
        int maxSec = parseInt(raw.getProperty("session_seconds", "45"), 45);
        final int endAfterTurns = parseInt(raw.getProperty("end_after_turns", "1"), 1);
        final boolean micEnabled = !"false".equalsIgnoreCase(raw.getProperty("mic_enabled", "true").trim());
        markVoiceBusy(true);

        final AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        try {
            String playMode = raw.getProperty("playback", "adapter").trim();
            pcmPlayer = new VoicePcmPlayer(ctx, playMode);
            pcmPlayer.setOnPlaybackDone(new Runnable() {
                @Override
                public void run() {
                    if (turnsPlayed >= endAfterTurns) {
                        Log.i(TAG, "playback done, end conversation");
                        endConversation = true;
                    }
                }
            });

            session = new GeminiLiveSession(cfg, new GeminiLiveSession.Listener() {
                @Override
                public void onSetupComplete() {
                    Log.i(TAG, "gemini setup complete");
                    geminiReady = true;
                    scheduleAudioRouteBoost(am);
                    GeminiLiveSession s = session;
                    if (s != null) {
                        s.sendUserText("你好，请用一句简短中文跟我打招呼，然后听我说话。");
                    }
                }

                @Override
                public void onAudioOut(byte[] pcm24) {
                    VoicePcmPlayer p = pcmPlayer;
                    if (p != null) p.playPcm24(pcm24);
                }

                @Override
                public void onInterrupted() {
                    VoicePcmPlayer p = pcmPlayer;
                    if (p != null) p.stop();
                }

                @Override
                public void onTurnComplete() {
                    Log.i(TAG, "gemini turn complete");
                    scheduleAudioRouteBoost(am);
                    VoicePcmPlayer p = pcmPlayer;
                    if (p != null) p.flushTurn();
                    turnsPlayed++;
                }

                @Override
                public void onClosed(String reason) {
                    Log.i(TAG, "gemini closed " + reason);
                    endConversation = true;
                }

                @Override
                public void onError(String msg) {
                    Log.e(TAG, "gemini err " + msg);
                    endConversation = true;
                }
            });
            Log.i(TAG, "gemini session starting gen=" + gen);
            session.start();
            scheduleAudioRouteBoost(am);

            long waitSetup = System.currentTimeMillis() + 30000;
            while (!geminiReady && !endConversation && System.currentTimeMillis() < waitSetup) {
                sleep(200);
            }
            if (!geminiReady) {
                Log.e(TAG, "gemini not ready (timeout or error), check GeminiLive logs");
                return;
            }

            final long deadline = System.currentTimeMillis() + maxSec * 1000L;
            if (micEnabled) {
                startMicAsync(gen, deadline);
            } else {
                Log.i(TAG, "mic disabled, playback-only session");
            }
            while (sessionActive && !endConversation && System.currentTimeMillis() < deadline) {
                sleep(300);
            }
            Log.i(TAG, "session loop exit gen=" + gen + " endConv=" + endConversation);
        } catch (Exception e) {
            Log.e(TAG, "session", e);
        } finally {
            endSession(gen);
            try {
                am.abandonAudioFocus(null);
            } catch (Exception ignored) {
            }
        }
    }

    /** 9420 后 adapter 占麦时, 主线程 new AudioRecord 可能永久阻塞。 */
    private void startMicAsync(final long gen, final long deadline) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                sleep(1200);
                if (endConversation || gen != conversationGeneration) return;
                try {
                    int minRec = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT);
                    AudioRecord ar = new AudioRecord(android.media.MediaRecorder.AudioSource.MIC, 16000,
                            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                            Math.max(minRec, 6400));
                    if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
                        Log.w(TAG, "mic init failed state=" + ar.getState());
                        ar.release();
                        return;
                    }
                    recorder = ar;
                    ar.startRecording();
                    Log.i(TAG, "mic start gen=" + gen);
                    byte[] buf = new byte[3200];
                    while (sessionActive && !endConversation
                            && System.currentTimeMillis() < deadline
                            && gen == conversationGeneration) {
                        GeminiLiveSession s = session;
                        if (s == null) break;
                        int n = ar.read(buf, 0, buf.length);
                        if (n > 0) s.sendPcm16k(copy(buf, n));
                    }
                } catch (Exception e) {
                    Log.w(TAG, "mic thread", e);
                }
            }
        }, "voice-mic");
        t.setDaemon(true);
        micThread = t;
        t.start();
    }

    private void forceResetSessionLocked() {
        endConversation = true;
        sessionActive = false;
        conversationGeneration++;
        try {
            if (session != null) session.close();
        } catch (Exception ignored) {
        }
        session = null;
        releaseMic();
        try {
            if (pcmPlayer != null) pcmPlayer.stop();
        } catch (Exception ignored) {
        }
        pcmPlayer = null;
        markVoiceBusy(false);
    }

    /** 勿在会话线程里同步调 AudioService，9420 唤醒后易卡死。 */
    private static void scheduleAudioRouteBoost(final AudioManager am) {
        if (am == null) return;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                sleep(300);
                boostPlaybackVolume(am);
            }
        }, "voice-audio-route");
        t.setDaemon(true);
        t.start();
    }

    private static void boostPlaybackVolume(AudioManager am) {
        if (am == null) return;
        try {
            am.setMode(AudioManager.MODE_NORMAL);
            am.setSpeakerphoneOn(true);
        } catch (Exception e) {
            Log.w(TAG, "audio mode", e);
        }
        try {
            am.requestAudioFocus(null, AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
        } catch (Exception e) {
            Log.w(TAG, "audio focus", e);
        }
        try {
            int maxMusic = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            am.setStreamVolume(AudioManager.STREAM_MUSIC, maxMusic, 0);
            int maxAlarm = am.getStreamMaxVolume(AudioManager.STREAM_ALARM);
            am.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarm, 0);
        } catch (Exception e) {
            Log.w(TAG, "volume", e);
        }
    }

    private void endSession(long gen) {
        synchronized (lock) {
            if (gen != conversationGeneration) {
                Log.i(TAG, "endSession stale gen=" + gen + " cur=" + conversationGeneration);
                return;
            }
            sessionActive = false;
        }
        Log.i(TAG, "endSession gen=" + gen);
        markVoiceBusy(false);
        endConversation = true;
        if (session != null) {
            session.close();
            session = null;
        }
        micThread = null;
        releaseMic();
        try {
            if (pcmPlayer != null) pcmPlayer.stop();
        } catch (Exception ignored) {
        }
        pcmPlayer = null;
        try {
            ctx.sendBroadcast(new Intent(ACTION_SESSION_END));
        } catch (Exception ignored) {
        }
    }

    private void releaseMic() {
        try {
            if (recorder != null) {
                recorder.stop();
                recorder.release();
            }
        } catch (Exception ignored) {
        }
        recorder = null;
    }

    private static byte[] copy(byte[] buf, int n) {
        if (n == buf.length) return buf;
        byte[] out = new byte[n];
        System.arraycopy(buf, 0, out, 0, n);
        return out;
    }

    private void pollWakeStamp() {
        while (true) {
            try {
                File f = new File(WAKE_STAMP);
                if (f.exists()) {
                    long ts = readStamp(f);
                    if (ts > lastStamp) {
                        lastStamp = ts;
                        Log.i(TAG, "wake stamp " + ts);
                        onWake9420();
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "stamp poll", e);
            }
            sleep(400);
        }
    }

    private static void markVoiceBusy(boolean busy) {
        try {
            File f = new File(VOICE_BUSY);
            if (busy) {
                new FileOutputStream(f).close();
            } else if (f.exists()) {
                f.delete();
            }
        } catch (Exception ignored) {
        }
    }

    private static long readStamp(File f) {
        try {
            BufferedReader br = new BufferedReader(new FileReader(f));
            String line = br.readLine();
            br.close();
            if (line != null) {
                return Long.parseLong(line.trim());
            }
        } catch (Exception ignored) {
        }
        return f.lastModified();
    }

    private Properties loadConfig() {
        Properties p = new Properties();
        File f = new File(ctx.getFilesDir(), "gemini.properties");
        if (!f.exists()) {
            writeDefaultConfig(f);
        }
        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            p.load(in);
        } catch (Exception e) {
            Log.e(TAG, "load config", e);
        } finally {
            if (in != null) try {
                in.close();
            } catch (Exception ignored) {
            }
        }
        return p;
    }

    private void writeDefaultConfig(File f) {
        try {
            FileOutputStream out = new FileOutputStream(f);
            String tpl = "# Gemini Live\n"
                    + "gemini_key=YOUR_GEMINI_API_KEY\n"
                    + "gemini_api_url=https://generativelanguage.googleapis.com\n"
                    + "model=gemini-2.5-flash-native-audio-preview-09-2025\n"
                    + "session_seconds=45\n"
                    + "end_after_turns=1\n"
                    + "mic_enabled=true\n"
                    + "playback=adapter\n"
                    + "system_instruction=你是腾讯听听智能音箱上的中文语音助手，回答简短自然。\n";
            out.write(tpl.getBytes("UTF-8"));
            out.close();
            f.setReadable(true, false);
            f.setWritable(true, false);
        } catch (Exception e) {
            Log.e(TAG, "write default config", e);
        }
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
