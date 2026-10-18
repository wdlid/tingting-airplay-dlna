package com.tingting.gemini9420;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** 由 root 侧 voice-wake-bridge.sh 在检测到 9420 后发送。 */
public class WakeReceiver extends BroadcastReceiver {
    private static final String TAG = "WakeReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (!VoiceAgentController.ACTION_WAKE.equals(intent.getAction())) return;
        Log.i(TAG, "WAKE_9420");
        Intent svc = new Intent(context, VoiceService.class);
        svc.putExtra("wake9420", true);
        context.startService(svc);
    }
}
