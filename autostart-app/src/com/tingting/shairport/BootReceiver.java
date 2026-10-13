package com.tingting.shairport;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机和网络变化时确保服务在运行 (startService 是幂等的) */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        context.startService(new Intent(context, ShairportService.class));
    }
}
