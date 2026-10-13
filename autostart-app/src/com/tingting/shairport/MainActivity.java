package com.tingting.shairport;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/** 无界面: 手动启动一次即可 (Android 3.1+ 未启动过的应用收不到 BOOT_COMPLETED), 然后立刻退出 */
public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        startService(new Intent(this, ShairportService.class));
        finish();
    }
}
