package com.tingting.gemini9420;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

/** 仅负责 Gemini Live 会话, 与 AirPlay/DLNA 无关。 */
public class VoiceService extends Service {
    private static final String TAG = "VoiceSvc";
    private VoiceAgentController voiceAgent;
    private PowerManager.WakeLock wakeLock;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        voiceAgent = new VoiceAgentController(this);
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "gemini9420-voice");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        } catch (Exception e) {
            Log.w(TAG, "wakeLock", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = new Notification.Builder(this)
                .setContentTitle("Gemini 9420")
                .setContentText("语音助手待命")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .build();
        startForeground(2, n);
        if (voiceAgent != null) {
            voiceAgent.startMonitoring();
            if (intent != null && intent.getBooleanExtra("wake9420", false)) {
                voiceAgent.onWake9420();
            }
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }
}
