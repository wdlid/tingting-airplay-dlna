package com.tingting.gemini9420;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Gemini PCM → 播放。Allwinner CedarX 在第三方 uid 下 MediaPlayer 音量=-1 无声,
 * 默认写 /data/local/tmp/gemini_tts.wav + .ready 由 adapter (system) 播放。
 */
final class VoicePcmPlayer {
    private static final String TAG = "VoicePcmPlayer";
    private static final int RATE = 24000;
    /** system adapter 可读，避免读 gemini cache 权限/SELinux 问题 */
    private static final String TTS_DIR = "/data/local/tmp";
    private static final String TTS_WAV = TTS_DIR + "/gemini_tts.wav";
    private static final String TTS_READY = TTS_DIR + "/gemini_tts.ready";

    private final Context ctx;
    private final Handler playHandler;
    private final String playbackMode;
    private final Object bufLock = new Object();
    private byte[] turnBuffer;
    private AudioTrack audioTrack;
    private Runnable onPlaybackDone;

    VoicePcmPlayer(Context ctx, String playbackMode) {
        this.ctx = ctx.getApplicationContext();
        this.playbackMode = playbackMode == null ? "adapter" : playbackMode.trim().toLowerCase();
        HandlerThread ht = new HandlerThread("voice-play");
        ht.start();
        playHandler = new Handler(ht.getLooper());
    }

    void setOnPlaybackDone(Runnable r) {
        onPlaybackDone = r;
    }

    void playPcm24(byte[] pcm24) {
        if (pcm24 == null || pcm24.length == 0) return;
        synchronized (bufLock) {
            int old = turnBuffer == null ? 0 : turnBuffer.length;
            byte[] nb = new byte[old + pcm24.length];
            if (old > 0) System.arraycopy(turnBuffer, 0, nb, 0, old);
            System.arraycopy(pcm24, 0, nb, old, pcm24.length);
            turnBuffer = nb;
        }
    }

    void flushTurn() {
        final byte[] pcm;
        synchronized (bufLock) {
            pcm = turnBuffer;
            turnBuffer = null;
        }
        if (pcm == null || pcm.length == 0) return;
        playHandler.post(new Runnable() {
            @Override
            public void run() {
                playTurnPcm(pcm);
            }
        });
    }

    void stop() {
        synchronized (bufLock) {
            turnBuffer = null;
        }
        playHandler.post(new Runnable() {
            @Override
            public void run() {
                releaseAudioTrack();
            }
        });
    }

    private void playTurnPcm(byte[] pcm24) {
        releaseAudioTrack();
        if (tryAdapterPlayback(pcm24)) return;
        if (playPcmAudioTrack(pcm24)) return;
        if ("adapter".equals(playbackMode)) {
            Log.e(TAG, "adapter+audiotrack failed pcm=" + pcm24.length);
        }
        Log.e(TAG, "playback failed pcm=" + pcm24.length);
        scheduleDoneFallback(pcm24.length);
    }

    private boolean tryAdapterPlayback(byte[] pcm24) {
        if ("audiotrack".equals(playbackMode)) return false;
        try {
            File dir = new File(TTS_DIR);
            if (!dir.isDirectory() && !dir.mkdirs()) {
                Log.w(TAG, "tts dir missing " + TTS_DIR);
                return false;
            }
            File tmp = new File(dir, "gemini_tts.wav.tmp");
            File wav = new File(TTS_WAV);
            if (wav.exists()) wav.delete();
            writeWav(tmp, pcm24);
            if (!tmp.renameTo(wav)) {
                writeWav(wav, pcm24);
                tmp.delete();
            }
            File ready = new File(TTS_READY);
            if (ready.exists()) ready.delete();
            if (ready.exists()) ready.delete();
            if (!ready.createNewFile()) {
                Log.w(TAG, "ready create failed");
                return false;
            }
            ready.setReadable(true, false);
            ready.setWritable(true, false);
            Log.i(TAG, "adapter queue wav bytes=" + pcm24.length);
            scheduleDoneFallback(pcm24.length);
            return true;
        } catch (Exception e) {
            Log.w(TAG, "adapter queue", e);
            return false;
        }
    }

    private void scheduleDoneFallback(int pcmBytes) {
        int ms = pcmBytes * 1000 / (RATE * 2);
        int delay = Math.max(ms + 1200, 2000);
        if ("adapter".equals(playbackMode)) {
            delay = Math.max(delay, ms + 2500);
        }
        playHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                notifyPlaybackDone();
            }
        }, delay);
    }

    private boolean playPcmAudioTrack(byte[] pcm24) {
        int[][] tries = new int[][]{
                {AudioManager.STREAM_MUSIC, AudioFormat.CHANNEL_OUT_STEREO, 48000},
                {AudioManager.STREAM_MUSIC, AudioFormat.CHANNEL_OUT_MONO, RATE},
                {AudioManager.STREAM_ALARM, AudioFormat.CHANNEL_OUT_MONO, RATE},
        };
        byte[] pcm = pcm24;
        for (int[] t : tries) {
            if (t[2] == 48000) pcm = upsample24kTo48kStereo(pcm24);
            if (playOneTrack(t[0], t[1], t[2], pcm)) return true;
        }
        return false;
    }

    private boolean playOneTrack(int stream, int ch, int rate, byte[] pcm) {
        try {
            int enc = AudioFormat.ENCODING_PCM_16BIT;
            int minBuf = AudioTrack.getMinBufferSize(rate, ch, enc);
            if (minBuf <= 0) minBuf = rate * 4;
            AudioTrack track = new AudioTrack(stream, rate, ch, enc,
                    Math.max(minBuf, pcm.length + 4096), AudioTrack.MODE_STREAM);
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                track.release();
                return false;
            }
            audioTrack = track;
            track.setStereoVolume(1.0f, 1.0f);
            track.play();
            int off = 0;
            while (off < pcm.length) {
                int n = track.write(pcm, off, pcm.length - off);
                if (n <= 0) break;
                off += n;
            }
            Log.i(TAG, "AudioTrack stream=" + stream + " rate=" + rate + " wrote=" + off);
            int bps = rate * (ch == AudioFormat.CHANNEL_OUT_MONO ? 2 : 4);
            int ms = off * 1000 / Math.max(bps, 1);
            playHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    notifyPlaybackDone();
                }
            }, Math.max(ms + 400, 800));
            return off > 0;
        } catch (Exception e) {
            Log.w(TAG, "AudioTrack stream=" + stream, e);
            releaseAudioTrack();
            return false;
        }
    }

    private static byte[] upsample24kTo48kStereo(byte[] pcm24) {
        int samples = pcm24.length / 2;
        byte[] out = new byte[samples * 2 * 2 * 2];
        int o = 0;
        for (int i = 0; i < samples; i++) {
            byte lo = pcm24[i * 2];
            byte hi = pcm24[i * 2 + 1];
            for (int r = 0; r < 2; r++) {
                out[o++] = lo;
                out[o++] = hi;
                out[o++] = lo;
                out[o++] = hi;
            }
        }
        return out;
    }

    private static void writeWav(File f, byte[] pcm) throws IOException {
        FileOutputStream out = null;
        try {
            int dataLen = pcm.length;
            out = new FileOutputStream(f);
            out.write("RIFF".getBytes("US-ASCII"));
            writeLe32(out, 36 + dataLen);
            out.write("WAVE".getBytes("US-ASCII"));
            out.write("fmt ".getBytes("US-ASCII"));
            writeLe32(out, 16);
            writeLe16(out, 1);
            writeLe16(out, 1);
            writeLe32(out, RATE);
            writeLe32(out, RATE * 2);
            writeLe16(out, 2);
            writeLe16(out, 16);
            out.write("data".getBytes("US-ASCII"));
            writeLe32(out, dataLen);
            out.write(pcm);
            out.flush();
            try {
                f.setReadable(true, false);
                f.setWritable(true, false);
            } catch (Exception ignored) {
            }
        } finally {
            if (out != null) try {
                out.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void writeLe16(FileOutputStream out, int v) throws IOException {
        out.write(v & 0xff);
        out.write((v >> 8) & 0xff);
    }

    private static void writeLe32(FileOutputStream out, int v) throws IOException {
        out.write(v & 0xff);
        out.write((v >> 8) & 0xff);
        out.write((v >> 16) & 0xff);
        out.write((v >> 24) & 0xff);
    }

    private void notifyPlaybackDone() {
        Runnable r = onPlaybackDone;
        if (r != null) r.run();
    }

    private void releaseAudioTrack() {
        if (audioTrack != null) {
            try {
                if (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
                    audioTrack.stop();
                }
            } catch (Exception ignored) {
            }
            try {
                audioTrack.release();
            } catch (Exception ignored) {
            }
            audioTrack = null;
        }
    }
}
