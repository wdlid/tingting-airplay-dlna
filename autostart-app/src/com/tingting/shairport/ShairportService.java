package com.tingting.shairport;

import android.app.Notification;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.Map;

/**
 * 常驻前台服务: 把 assets 里的静态 shairport-sync 释放到私有目录并运行,
 * 等 Wi-Fi 就绪后再启动 (mDNS 需要网卡有 IP), 异常退出自动重启。
 */
public class ShairportService extends Service {
    private static final String TAG = "ShairportSvc";
    private static final long MAX_LOG = 512 * 1024;

    private Thread worker;
    private volatile boolean running;
    private volatile Process proc;
    private DlnaRenderer dlna;
    private WifiManager.MulticastLock mcastLock;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = new Notification.Builder(this)
                .setContentTitle("AirPlay Receiver")
                .setContentText("TingTing 正在运行")
                .setSmallIcon(android.R.drawable.ic_media_play)
                .build();
        startForeground(1, n);

        if (worker == null) {
            running = true;
            acquireLocks();
            dlna = new DlnaRenderer(this);
            dlna.start();
            worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    loop();
                }
            }, "shairport-runner");
            worker.start();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (dlna != null) {
            dlna.stop();
        }
        Process p = proc;
        if (p != null) {
            p.destroy();
        }
        releaseLocks();
        super.onDestroy();
    }

    private void acquireLocks() {
        try {
            WifiManager wm = (WifiManager) getSystemService(Context.WIFI_SERVICE);
            mcastLock = wm.createMulticastLock("shairport-mdns");
            mcastLock.setReferenceCounted(false);
            mcastLock.acquire();
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL, "shairport-wifi");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "shairport-cpu");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        } catch (Exception e) {
            Log.e(TAG, "acquireLocks failed", e);
        }
    }

    private void releaseLocks() {
        try {
            if (mcastLock != null && mcastLock.isHeld()) mcastLock.release();
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception e) {
            Log.e(TAG, "releaseLocks failed", e);
        }
    }

    private void copyAsset(String name, File dst, boolean exec) throws Exception {
        InputStream in = getAssets().open(name);
        long assetLen = 0;
        byte[] buf = new byte[65536];
        File tmp = new File(dst.getPath() + ".tmp");
        OutputStream out = new FileOutputStream(tmp);
        int r;
        while ((r = in.read(buf)) > 0) {
            out.write(buf, 0, r);
            assetLen += r;
        }
        out.close();
        in.close();
        if (dst.exists() && dst.length() == assetLen && !name.endsWith(".conf")) {
            tmp.delete();
        } else {
            if (dst.exists()) dst.delete();
            if (!tmp.renameTo(dst)) throw new Exception("rename failed for " + name);
        }
        if (exec) {
            dst.setExecutable(true, false);
        }
        dst.setReadable(true, false);
    }

    /** 应用被强杀后遗留的 shairport-sync 孤儿进程(同 uid)会占着 5000 端口, 启动前先清掉 */
    private void killStale(String binPath) {
        try {
            Process p = Runtime.getRuntime().exec("ps");
            java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            String line;
            int me = android.os.Process.myPid();
            while ((line = br.readLine()) != null) {
                if (!line.contains(binPath)) continue;
                String[] f = line.trim().split("\\s+");
                if (f.length > 2) {
                    int pid = Integer.parseInt(f[1]);
                    if (pid != me) {
                        Log.i(TAG, "kill stale shairport pid " + pid);
                        android.os.Process.killProcess(pid);
                    }
                }
            }
            p.waitFor();
            Thread.sleep(500);
        } catch (Exception e) {
            Log.w(TAG, "killStale", e);
        }
    }

    private boolean wifiReady() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                if (!ni.getName().startsWith("wlan") && !ni.getName().startsWith("eth")) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) return true;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "wifiReady", e);
        }
        return false;
    }

    private void loop() {
        File dir = getFilesDir();
        File bin = new File(dir, "shairport-sync");
        File conf = new File(dir, "shairport-sync.conf");
        File log = new File(dir, "sp.log");
        killStale(bin.getPath());
        try {
            copyAsset("shairport-sync", bin, true);
            copyAsset("shairport-sync.conf", conf, false);
        } catch (Exception e) {
            Log.e(TAG, "install failed", e);
            return;
        }

        while (running) {
            while (running && !wifiReady()) {
                sleep(3000);
            }
            if (!running) break;
            sleep(3000); // 让 DHCP/路由稳定

            try {
                ProcessBuilder pb = new ProcessBuilder(bin.getPath(), "-c", conf.getPath(), "-o", "alsa");
                pb.directory(dir);
                Map<String, String> env = pb.environment();
                env.put("TMPDIR", dir.getPath());
                pb.redirectErrorStream(true);
                proc = pb.start();
                Log.i(TAG, "shairport-sync started");

                InputStream is = proc.getInputStream();
                byte[] buf = new byte[4096];
                OutputStream lo = new FileOutputStream(log, false);
                long written = 0;
                int r;
                while ((r = is.read(buf)) > 0) {
                    if (written > MAX_LOG) {
                        lo.close();
                        lo = new FileOutputStream(log, false);
                        written = 0;
                    }
                    lo.write(buf, 0, r);
                    written += r;
                }
                lo.close();
                int code = proc.waitFor();
                Log.w(TAG, "shairport-sync exited with " + code);
            } catch (Exception e) {
                Log.e(TAG, "run failed", e);
            }
            proc = null;
            sleep(3000);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
