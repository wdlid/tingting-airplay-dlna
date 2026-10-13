package com.tingting.shairport;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.cert.X509Certificate;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 最小可用的 UPnP/DLNA MediaRenderer (DMR):
 * SSDP 发现 + 设备/服务描述 + SOAP 控制 (AVTransport/RenderingControl/ConnectionManager)
 * + GENA 事件订阅, 用 Android MediaPlayer 播放 http 音频流。
 */
public class DlnaRenderer {
    private static final String TAG = "DlnaRenderer";
    private static final String NAME = "TingTing";
    private static final String SSDP_ADDR = "239.255.255.250";
    private static final int SSDP_PORT = 1900;
    private static final String SERVER = "Linux/3.4 UPnP/1.0 TingTingDMR/1.0";

    private static final String AVT = "urn:schemas-upnp-org:service:AVTransport:1";
    private static final String RCS = "urn:schemas-upnp-org:service:RenderingControl:1";
    private static final String CMS = "urn:schemas-upnp-org:service:ConnectionManager:1";
    private static final String DEV = "urn:schemas-upnp-org:device:MediaRenderer:1";

    private static final String SINK =
            "http-get:*:audio/mpeg:*,http-get:*:audio/mp3:*,http-get:*:audio/x-mpeg:*,"
                    + "http-get:*:audio/flac:*,http-get:*:audio/x-flac:*,http-get:*:audio/wav:*,"
                    + "http-get:*:audio/x-wav:*,http-get:*:audio/L16:*,http-get:*:audio/mp4:*,"
                    + "http-get:*:audio/x-m4a:*,http-get:*:audio/aac:*,http-get:*:audio/ogg:*,"
                    + "http-get:*:audio/x-ogg:*,http-get:*:application/ogg:*,http-get:*:audio/3gpp:*,"
                    + "http-get:*:audio/amr:*,http-get:*:audio/*:*";

    private final Context ctx;
    private final String uuid;
    private final Random rnd = new Random();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile boolean running;
    private ServerSocket httpServer;
    private int httpPort;
    private MulticastSocket ssdp;
    private volatile String lastIp = "";

    // ---- 播放器状态 ----
    private final HandlerThread playerThread = new HandlerThread("dlna-player");
    private Handler ph;
    private MediaPlayer mp;
    private volatile boolean prepared;
    private volatile boolean wantPlay;
    private volatile String transportState = "NO_MEDIA_PRESENT";
    private volatile String curUri = "";
    private volatile String curMeta = "";
    private volatile String nextUri = "";
    private volatile String nextMeta = "";
    private volatile int volume = -1;
    private volatile boolean mute;
    private int preMuteVolume = 50;
    private PowerManager.WakeLock wakeLock;

    // ---- 事件订阅 ----
    private static class Sub {
        String sid;
        String service;
        String callback;
        long expire;
        int seq;
    }

    private final List<Sub> subs = new ArrayList<Sub>();

    public DlnaRenderer(Context c) {
        this.ctx = c.getApplicationContext();
        SharedPreferences sp = ctx.getSharedPreferences("dlna", Context.MODE_PRIVATE);
        String u = sp.getString("uuid", null);
        if (u == null) {
            u = UUID.randomUUID().toString();
            sp.edit().putString("uuid", u).commit();
        }
        uuid = u;
    }

    // =====================================================================
    public void start() {
        if (running) return;
        running = true;
        playerThread.start();
        ph = new Handler(playerThread.getLooper());
        pool.execute(new Runnable() {
            public void run() {
                try {
                    mainLoop();
                } catch (Exception e) {
                    Log.e(TAG, "mainLoop died", e);
                }
            }
        });
    }

    public void stop() {
        running = false;
        try {
            sendNotify("ssdp:byebye");
        } catch (Exception ignored) {
        }
        try {
            if (ssdp != null) ssdp.close();
        } catch (Exception ignored) {
        }
        try {
            if (httpServer != null) httpServer.close();
        } catch (Exception ignored) {
        }
        ph.post(new Runnable() {
            public void run() {
                releasePlayer();
            }
        });
        pool.shutdown();
    }

    private String ip() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName();
                if (!n.startsWith("wlan") && !n.startsWith("eth")) continue;
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) return a.getHostAddress();
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "ip()", e);
        }
        return null;
    }

    private NetworkInterface netif() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                if (ni.isUp() && !ni.isLoopback() && (ni.getName().startsWith("wlan") || ni.getName().startsWith("eth")))
                    return ni;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void mainLoop() throws Exception {
        // HTTP 服务器
        httpServer = new ServerSocket();
        httpServer.setReuseAddress(true);
        try {
            httpServer.bind(new InetSocketAddress(49152));
        } catch (Exception e) {
            httpServer.close();
            httpServer = new ServerSocket(0);
        }
        httpPort = httpServer.getLocalPort();
        Log.i(TAG, "HTTP on port " + httpPort);
        pool.execute(new Runnable() {
            public void run() {
                acceptLoop();
            }
        });

        long lastAlive = 0;
        while (running) {
            String ip = ip();
            if (ip == null) {
                lastIp = "";
                sleep(3000);
                continue;
            }
            if (!ip.equals(lastIp) || ssdp == null || ssdp.isClosed()) {
                lastIp = ip;
                openSsdp();
                sendNotify("ssdp:alive");
                sendNotify("ssdp:alive");
                lastAlive = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - lastAlive > 60000) {
                sendNotify("ssdp:alive");
                lastAlive = System.currentTimeMillis();
            }
            expireSubs();
            sleep(2000);
        }
    }

    // =====================================================================
    // SSDP
    private List<String[]> targets() {
        List<String[]> l = new ArrayList<String[]>();
        l.add(new String[]{"upnp:rootdevice", "uuid:" + uuid + "::upnp:rootdevice"});
        l.add(new String[]{"uuid:" + uuid, "uuid:" + uuid});
        l.add(new String[]{DEV, "uuid:" + uuid + "::" + DEV});
        l.add(new String[]{AVT, "uuid:" + uuid + "::" + AVT});
        l.add(new String[]{RCS, "uuid:" + uuid + "::" + RCS});
        l.add(new String[]{CMS, "uuid:" + uuid + "::" + CMS});
        return l;
    }

    private synchronized void openSsdp() {
        try {
            if (ssdp != null) ssdp.close();
        } catch (Exception ignored) {
        }
        try {
            final MulticastSocket s = new MulticastSocket(null);
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(SSDP_PORT));
            s.setTimeToLive(4);
            NetworkInterface ni = netif();
            if (ni != null) {
                try {
                    s.setNetworkInterface(ni);
                } catch (Exception ignored) {
                }
                s.joinGroup(new InetSocketAddress(InetAddress.getByName(SSDP_ADDR), SSDP_PORT), ni);
            } else {
                s.joinGroup(InetAddress.getByName(SSDP_ADDR));
            }
            ssdp = s;
            pool.execute(new Runnable() {
                public void run() {
                    ssdpLoop(s);
                }
            });
            Log.i(TAG, "SSDP joined, ip=" + lastIp);
        } catch (Exception e) {
            Log.e(TAG, "openSsdp failed", e);
            ssdp = null;
        }
    }

    private void ssdpLoop(MulticastSocket s) {
        byte[] buf = new byte[2048];
        while (running && !s.isClosed()) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                s.receive(p);
                String msg = new String(p.getData(), 0, p.getLength(), "UTF-8");
                if (!msg.startsWith("M-SEARCH")) continue;
                String st = header(msg, "ST");
                if (st == null) continue;
                int mx = 2;
                try {
                    mx = Integer.parseInt(header(msg, "MX").trim());
                } catch (Exception ignored) {
                }
                respondSearch(s, p.getAddress(), p.getPort(), st.trim(), Math.max(1, Math.min(mx, 3)));
            } catch (Exception e) {
                if (running && !s.isClosed()) Log.w(TAG, "ssdpLoop", e);
            }
        }
    }

    private static String header(String msg, String name) {
        for (String line : msg.split("\r\n")) {
            int i = line.indexOf(':');
            if (i > 0 && line.substring(0, i).trim().equalsIgnoreCase(name)) return line.substring(i + 1).trim();
        }
        return null;
    }

    private void respondSearch(final MulticastSocket s, final InetAddress to, final int port, final String st, final int mx) {
        final List<String[]> matched = new ArrayList<String[]>();
        for (String[] t : targets()) {
            if (st.equals("ssdp:all") || st.equals(t[0])) matched.add(t);
        }
        if (matched.isEmpty()) return;
        pool.execute(new Runnable() {
            public void run() {
                sleep(rnd.nextInt(mx * 400 + 1));
                for (String[] t : matched) {
                    String r = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nDATE: " + httpDate()
                            + "\r\nEXT:\r\nLOCATION: http://" + lastIp + ":" + httpPort + "/desc.xml\r\nSERVER: " + SERVER
                            + "\r\nST: " + t[0] + "\r\nUSN: " + t[1] + "\r\n\r\n";
                    try {
                        byte[] b = r.getBytes("UTF-8");
                        s.send(new DatagramPacket(b, b.length, to, port));
                    } catch (Exception e) {
                        Log.w(TAG, "respond", e);
                    }
                }
            }
        });
    }

    private void sendNotify(String nts) throws Exception {
        MulticastSocket s = ssdp;
        if (s == null || s.isClosed() || lastIp.length() == 0) return;
        InetAddress grp = InetAddress.getByName(SSDP_ADDR);
        for (String[] t : targets()) {
            StringBuilder sb = new StringBuilder("NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n");
            if (nts.equals("ssdp:alive")) {
                sb.append("CACHE-CONTROL: max-age=1800\r\nLOCATION: http://").append(lastIp).append(':').append(httpPort)
                        .append("/desc.xml\r\nSERVER: ").append(SERVER).append("\r\n");
            }
            sb.append("NT: ").append(t[0]).append("\r\nNTS: ").append(nts).append("\r\nUSN: ").append(t[1]).append("\r\n\r\n");
            byte[] b = sb.toString().getBytes("UTF-8");
            s.send(new DatagramPacket(b, b.length, grp, SSDP_PORT));
        }
    }

    private static String httpDate() {
        SimpleDateFormat f = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("GMT"));
        return f.format(new Date());
    }

    // =====================================================================
    // HTTP
    private void acceptLoop() {
        while (running) {
            try {
                final Socket c = httpServer.accept();
                pool.execute(new Runnable() {
                    public void run() {
                        handleHttp(c);
                    }
                });
            } catch (Exception e) {
                if (running) Log.w(TAG, "accept", e);
                if (httpServer == null || httpServer.isClosed()) return;
            }
        }
    }

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') bo.write(c);
        }
        if (c < 0 && bo.size() == 0) return null;
        return bo.toString("UTF-8");
    }

    private void handleHttp(Socket sock) {
        try {
            sock.setSoTimeout(15000);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            OutputStream out = sock.getOutputStream();
            String req = readLine(in);
            if (req == null || req.length() == 0) return;
            String[] parts = req.split(" ");
            String method = parts[0];
            String path = parts.length > 1 ? parts[1] : "/";
            Map<String, String> h = new HashMap<String, String>();
            String line;
            while ((line = readLine(in)) != null && line.length() > 0) {
                int i = line.indexOf(':');
                if (i > 0) h.put(line.substring(0, i).trim().toLowerCase(Locale.US), line.substring(i + 1).trim());
            }
            byte[] body = new byte[0];
            String cl = h.get("content-length");
            if (cl != null) {
                int n = Integer.parseInt(cl);
                body = new byte[n];
                int off = 0;
                while (off < n) {
                    int r = in.read(body, off, n - off);
                    if (r < 0) break;
                    off += r;
                }
            } else if ("chunked".equalsIgnoreCase(h.get("transfer-encoding"))) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                while (true) {
                    String sz = readLine(in);
                    if (sz == null) break;
                    int n = Integer.parseInt(sz.trim().split(";")[0], 16);
                    if (n == 0) {
                        readLine(in);
                        break;
                    }
                    byte[] ck = new byte[n];
                    int off = 0;
                    while (off < n) {
                        int r = in.read(ck, off, n - off);
                        if (r < 0) break;
                        off += r;
                    }
                    bo.write(ck, 0, off);
                    readLine(in);
                }
                body = bo.toByteArray();
            }

            if ((method.equals("GET") || method.equals("HEAD")) && path.startsWith("/proxy?u=")) {
                handleProxy(out, path, h, method);
                return;
            }
            if (method.equals("GET") || method.equals("HEAD")) {
                String xml = null;
                if (path.equals("/desc.xml")) xml = deviceXml();
                else if (path.equals("/AVTransport.xml")) xml = scpd(AVT_ACTIONS, AVT_VARS);
                else if (path.equals("/RenderingControl.xml")) xml = scpd(RCS_ACTIONS, RCS_VARS);
                else if (path.equals("/ConnectionManager.xml")) xml = scpd(CMS_ACTIONS, CMS_VARS);
                if (xml == null) {
                    reply(out, 404, "Not Found", "text/plain", "not found", null);
                } else {
                    reply(out, 200, "OK", "text/xml; charset=\"utf-8\"", method.equals("HEAD") ? "" : xml, null);
                }
            } else if (method.equals("POST")) {
                handleSoap(out, path, h, new String(body, "UTF-8"));
            } else if (method.equals("SUBSCRIBE")) {
                handleSubscribe(out, path, h);
            } else if (method.equals("UNSUBSCRIBE")) {
                String sid = h.get("sid");
                synchronized (subs) {
                    for (int i = subs.size() - 1; i >= 0; i--) if (subs.get(i).sid.equals(sid)) subs.remove(i);
                }
                reply(out, 200, "OK", "text/plain", "", null);
            } else {
                reply(out, 405, "Method Not Allowed", "text/plain", "", null);
            }
            out.flush();
        } catch (Exception e) {
            Log.w(TAG, "http: " + e);
        } finally {
            try {
                sock.close();
            } catch (Exception ignored) {
            }
        }
    }

    // ---- https 代理 ----
    // 此设备的厂商播放器(Cedarx)只会 TLS1.0, 访问现代 https 站点会 "alert protocol version";
    // 这里用 Java 的 TLS1.2 取流, 再以 http://127.0.0.1 转给 MediaPlayer。
    // 证书校验放宽(旧系统根证书过期), 仅用于播放音频流。
    private static SSLSocketFactory tlsFactory;

    private static synchronized SSLSocketFactory tlsFactory() throws Exception {
        if (tlsFactory == null) {
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {
                }

                public void checkServerTrusted(X509Certificate[] c, String a) {
                }

                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }}, null);
            final SSLSocketFactory base = sc.getSocketFactory();
            tlsFactory = new SSLSocketFactory() {
                private Socket en(Socket s) {
                    if (s instanceof SSLSocket) {
                        ((SSLSocket) s).setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.1", "TLSv1"});
                        ((SSLSocket) s).setEnabledCipherSuites(((SSLSocket) s).getSupportedCipherSuites());
                        Log.i(TAG, "tls protos=" + java.util.Arrays.toString(((SSLSocket) s).getSupportedProtocols()));
                    }
                    return s;
                }

                public String[] getDefaultCipherSuites() {
                    return base.getDefaultCipherSuites();
                }

                public String[] getSupportedCipherSuites() {
                    return base.getSupportedCipherSuites();
                }

                public Socket createSocket(Socket s, String h, int p, boolean ac) throws java.io.IOException {
                    Socket r = en(base.createSocket(s, h, p, ac));
                    try { // SNI: 现代站点必须带 server_name
                        r.getClass().getMethod("setHostname", String.class).invoke(r, h);
                        Log.i(TAG, "sni set " + h);
                    } catch (Throwable t) {
                        Log.w(TAG, "sni fail " + t);
                    }
                    return r;
                }

                public Socket createSocket(String h, int p) throws java.io.IOException {
                    return en(base.createSocket(h, p));
                }

                public Socket createSocket(String h, int p, InetAddress la, int lp) throws java.io.IOException {
                    return en(base.createSocket(h, p, la, lp));
                }

                public Socket createSocket(InetAddress h, int p) throws java.io.IOException {
                    return en(base.createSocket(h, p));
                }

                public Socket createSocket(InetAddress h, int p, InetAddress la, int lp) throws java.io.IOException {
                    return en(base.createSocket(h, p, la, lp));
                }
            };
        }
        return tlsFactory;
    }

    private void handleProxy(OutputStream out, String path, Map<String, String> h, String method) throws Exception {
        String target = java.net.URLDecoder.decode(path.substring("/proxy?u=".length()), "UTF-8");
        HttpURLConnection c = null;
        int code = 0;
        for (int hop = 0; hop < 6; hop++) {
            c = (HttpURLConnection) new URL(target).openConnection();
            if (c instanceof HttpsURLConnection) {
                ((HttpsURLConnection) c).setSSLSocketFactory(tlsFactory());
                ((HttpsURLConnection) c).setHostnameVerifier(new javax.net.ssl.HostnameVerifier() {
                    public boolean verify(String hostname, javax.net.ssl.SSLSession session) {
                        return true;
                    }
                });
            }
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setRequestMethod(method.equals("HEAD") ? "HEAD" : "GET");
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 4.4.2) TingTingDMR");
            String rg = h.get("range");
            if (rg != null) c.setRequestProperty("Range", rg);
            code = c.getResponseCode();
            if (code >= 300 && code < 400 && c.getHeaderField("Location") != null) {
                target = new URL(new URL(target), c.getHeaderField("Location")).toString();
                c.disconnect();
                continue;
            }
            break;
        }
        Log.i(TAG, "proxy " + code + " " + target);
        StringBuilder sb = new StringBuilder("HTTP/1.1 " + code + " OK\r\n");
        String[] keep = {"Content-Type", "Content-Length", "Content-Range", "Accept-Ranges"};
        for (String k : keep) {
            String v = c.getHeaderField(k);
            if (v != null) sb.append(k).append(": ").append(v).append("\r\n");
        }
        sb.append("Connection: close\r\n\r\n");
        out.write(sb.toString().getBytes("UTF-8"));
        if (method.equals("HEAD")) {
            c.disconnect();
            return;
        }
        InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (is != null) {
            byte[] buf = new byte[16384];
            int r;
            try {
                while ((r = is.read(buf)) > 0) {
                    out.write(buf, 0, r);
                }
            } catch (java.io.IOException e) {
                // 播放器 seek/关闭连接, 正常
            }
            is.close();
        }
        c.disconnect();
    }

    private void reply(OutputStream out, int code, String msg, String type, String body, String extra) throws Exception {
        byte[] b = body.getBytes("UTF-8");
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(' ').append(msg).append("\r\n");
        sb.append("CONTENT-TYPE: ").append(type).append("\r\n");
        sb.append("CONTENT-LENGTH: ").append(b.length).append("\r\n");
        sb.append("DATE: ").append(httpDate()).append("\r\nEXT:\r\nSERVER: ").append(SERVER).append("\r\n");
        if (extra != null) sb.append(extra);
        sb.append("CONNECTION: close\r\n\r\n");
        out.write(sb.toString().getBytes("UTF-8"));
        out.write(b);
    }

    private String deviceXml() {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\"?>\n<root xmlns=\"urn:schemas-upnp-org:device-1-0\" xmlns:dlna=\"urn:schemas-dlna-org:device-1-0\">");
        sb.append("<specVersion><major>1</major><minor>0</minor></specVersion><device>");
        sb.append("<dlna:X_DLNADOC>DMR-1.50</dlna:X_DLNADOC>");
        sb.append("<deviceType>").append(DEV).append("</deviceType>");
        sb.append("<friendlyName>").append(esc(NAME)).append("</friendlyName>");
        sb.append("<manufacturer>TingTing</manufacturer><modelDescription>TingTing DLNA Renderer</modelDescription>");
        sb.append("<modelName>TingTing DMR</modelName><modelNumber>1.0</modelNumber>");
        sb.append("<UDN>uuid:").append(uuid).append("</UDN><serviceList>");
        svc(sb, "AVTransport", AVT);
        svc(sb, "RenderingControl", RCS);
        svc(sb, "ConnectionManager", CMS);
        sb.append("</serviceList></device></root>");
        return sb.toString();
    }

    private static void svc(StringBuilder sb, String name, String type) {
        sb.append("<service><serviceType>").append(type).append("</serviceType><serviceId>urn:upnp-org:serviceId:")
                .append(name).append("</serviceId><SCPDURL>/").append(name).append(".xml</SCPDURL><controlURL>/ctl/")
                .append(name).append("</controlURL><eventSubURL>/evt/").append(name).append("</eventSubURL></service>");
    }

    // =====================================================================
    // SCPD 定义: "动作名|in参数:关联变量,...|out参数:关联变量,..."
    private static final String[] AVT_ACTIONS = {
            "SetAVTransportURI|InstanceID:A_ARG_TYPE_InstanceID,CurrentURI:AVTransportURI,CurrentURIMetaData:AVTransportURIMetaData|",
            "SetNextAVTransportURI|InstanceID:A_ARG_TYPE_InstanceID,NextURI:NextAVTransportURI,NextURIMetaData:NextAVTransportURIMetaData|",
            "GetMediaInfo|InstanceID:A_ARG_TYPE_InstanceID|NrTracks:NumberOfTracks,MediaDuration:CurrentMediaDuration,CurrentURI:AVTransportURI,CurrentURIMetaData:AVTransportURIMetaData,NextURI:NextAVTransportURI,NextURIMetaData:NextAVTransportURIMetaData,PlayMedium:PlaybackStorageMedium,RecordMedium:RecordStorageMedium,WriteStatus:RecordMediumWriteStatus",
            "GetTransportInfo|InstanceID:A_ARG_TYPE_InstanceID|CurrentTransportState:TransportState,CurrentTransportStatus:TransportStatus,CurrentSpeed:TransportPlaySpeed",
            "GetPositionInfo|InstanceID:A_ARG_TYPE_InstanceID|Track:CurrentTrack,TrackDuration:CurrentTrackDuration,TrackMetaData:CurrentTrackMetaData,TrackURI:CurrentTrackURI,RelTime:RelativeTimePosition,AbsTime:AbsoluteTimePosition,RelCount:RelativeCounterPosition,AbsCount:AbsoluteCounterPosition",
            "GetDeviceCapabilities|InstanceID:A_ARG_TYPE_InstanceID|PlayMedia:PossiblePlaybackStorageMedia,RecMedia:PossibleRecordStorageMedia,RecQualityModes:PossibleRecordQualityModes",
            "GetTransportSettings|InstanceID:A_ARG_TYPE_InstanceID|PlayMode:CurrentPlayMode,RecQualityMode:CurrentRecordQualityMode",
            "Stop|InstanceID:A_ARG_TYPE_InstanceID|",
            "Play|InstanceID:A_ARG_TYPE_InstanceID,Speed:TransportPlaySpeed|",
            "Pause|InstanceID:A_ARG_TYPE_InstanceID|",
            "Seek|InstanceID:A_ARG_TYPE_InstanceID,Unit:A_ARG_TYPE_SeekMode,Target:A_ARG_TYPE_SeekTarget|",
            "Next|InstanceID:A_ARG_TYPE_InstanceID|",
            "Previous|InstanceID:A_ARG_TYPE_InstanceID|",
            "GetCurrentTransportActions|InstanceID:A_ARG_TYPE_InstanceID|Actions:CurrentTransportActions"
    };
    // "变量名|类型|是否事件|允许值(逗号)|范围min,max,step"
    private static final String[] AVT_VARS = {
            "TransportState|string|no|STOPPED,PLAYING,PAUSED_PLAYBACK,TRANSITIONING,NO_MEDIA_PRESENT|",
            "TransportStatus|string|no|OK,ERROR_OCCURRED|",
            "PlaybackStorageMedium|string|no|NONE,NETWORK|",
            "RecordStorageMedium|string|no||",
            "PossiblePlaybackStorageMedia|string|no||",
            "PossibleRecordStorageMedia|string|no||",
            "CurrentPlayMode|string|no|NORMAL|",
            "TransportPlaySpeed|string|no|1|",
            "RecordMediumWriteStatus|string|no||",
            "PossibleRecordQualityModes|string|no||",
            "CurrentRecordQualityMode|string|no||",
            "NumberOfTracks|ui4|no||0,1,1",
            "CurrentTrack|ui4|no||0,1,1",
            "CurrentTrackDuration|string|no||",
            "CurrentMediaDuration|string|no||",
            "CurrentTrackMetaData|string|no||",
            "CurrentTrackURI|string|no||",
            "AVTransportURI|string|no||",
            "AVTransportURIMetaData|string|no||",
            "NextAVTransportURI|string|no||",
            "NextAVTransportURIMetaData|string|no||",
            "RelativeTimePosition|string|no||",
            "AbsoluteTimePosition|string|no||",
            "RelativeCounterPosition|i4|no||",
            "AbsoluteCounterPosition|ui4|no||",
            "CurrentTransportActions|string|no||",
            "LastChange|string|yes||",
            "A_ARG_TYPE_SeekMode|string|no|REL_TIME,TRACK_NR|",
            "A_ARG_TYPE_SeekTarget|string|no||",
            "A_ARG_TYPE_InstanceID|ui4|no||"
    };
    private static final String[] RCS_ACTIONS = {
            "ListPresets|InstanceID:A_ARG_TYPE_InstanceID|CurrentPresetNameList:PresetNameList",
            "SelectPreset|InstanceID:A_ARG_TYPE_InstanceID,PresetName:A_ARG_TYPE_PresetName|",
            "GetMute|InstanceID:A_ARG_TYPE_InstanceID,Channel:A_ARG_TYPE_Channel|CurrentMute:Mute",
            "SetMute|InstanceID:A_ARG_TYPE_InstanceID,Channel:A_ARG_TYPE_Channel,DesiredMute:Mute|",
            "GetVolume|InstanceID:A_ARG_TYPE_InstanceID,Channel:A_ARG_TYPE_Channel|CurrentVolume:Volume",
            "SetVolume|InstanceID:A_ARG_TYPE_InstanceID,Channel:A_ARG_TYPE_Channel,DesiredVolume:Volume|"
    };
    private static final String[] RCS_VARS = {
            "PresetNameList|string|no||",
            "LastChange|string|yes||",
            "Mute|boolean|no||",
            "Volume|ui2|no||0,100,1",
            "A_ARG_TYPE_Channel|string|no|Master|",
            "A_ARG_TYPE_InstanceID|ui4|no||",
            "A_ARG_TYPE_PresetName|string|no|FactoryDefaults|"
    };
    private static final String[] CMS_ACTIONS = {
            "GetProtocolInfo||Source:SourceProtocolInfo,Sink:SinkProtocolInfo",
            "GetCurrentConnectionIDs||ConnectionIDs:CurrentConnectionIDs",
            "GetCurrentConnectionInfo|ConnectionID:A_ARG_TYPE_ConnectionID|RcsID:A_ARG_TYPE_RcsID,AVTransportID:A_ARG_TYPE_AVTransportID,ProtocolInfo:A_ARG_TYPE_ProtocolInfo,PeerConnectionManager:A_ARG_TYPE_ConnectionManager,PeerConnectionID:A_ARG_TYPE_ConnectionID,Direction:A_ARG_TYPE_Direction,Status:A_ARG_TYPE_ConnectionStatus"
    };
    private static final String[] CMS_VARS = {
            "SourceProtocolInfo|string|yes||",
            "SinkProtocolInfo|string|yes||",
            "CurrentConnectionIDs|string|yes||",
            "A_ARG_TYPE_ConnectionStatus|string|no|OK,ContentFormatMismatch,InsufficientBandwidth,UnreliableChannel,Unknown|",
            "A_ARG_TYPE_ConnectionManager|string|no||",
            "A_ARG_TYPE_Direction|string|no|Input,Output|",
            "A_ARG_TYPE_ProtocolInfo|string|no||",
            "A_ARG_TYPE_ConnectionID|i4|no||",
            "A_ARG_TYPE_AVTransportID|i4|no||",
            "A_ARG_TYPE_RcsID|i4|no||"
    };

    private static String scpd(String[] actions, String[] vars) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\"?>\n<scpd xmlns=\"urn:schemas-upnp-org:service-1-0\">");
        sb.append("<specVersion><major>1</major><minor>0</minor></specVersion><actionList>");
        for (String a : actions) {
            String[] p = a.split("\\|", -1);
            sb.append("<action><name>").append(p[0]).append("</name><argumentList>");
            args(sb, p[1], "in");
            args(sb, p[2], "out");
            sb.append("</argumentList></action>");
        }
        sb.append("</actionList><serviceStateTable>");
        for (String v : vars) {
            String[] p = v.split("\\|", -1);
            sb.append("<stateVariable sendEvents=\"").append(p[2]).append("\"><name>").append(p[0]).append("</name><dataType>")
                    .append(p[1]).append("</dataType>");
            if (p[3].length() > 0) {
                sb.append("<allowedValueList>");
                for (String av : p[3].split(",")) sb.append("<allowedValue>").append(av).append("</allowedValue>");
                sb.append("</allowedValueList>");
            }
            if (p.length > 4 && p[4].length() > 0) {
                String[] r = p[4].split(",");
                sb.append("<allowedValueRange><minimum>").append(r[0]).append("</minimum><maximum>").append(r[1])
                        .append("</maximum><step>").append(r[2]).append("</step></allowedValueRange>");
            }
            sb.append("</stateVariable>");
        }
        sb.append("</serviceStateTable></scpd>");
        return sb.toString();
    }

    private static void args(StringBuilder sb, String spec, String dir) {
        if (spec.length() == 0) return;
        for (String a : spec.split(",")) {
            String[] n = a.split(":");
            sb.append("<argument><name>").append(n[0]).append("</name><direction>").append(dir)
                    .append("</direction><relatedStateVariable>").append(n[1]).append("</relatedStateVariable></argument>");
        }
    }

    // =====================================================================
    // SOAP
    private void handleSoap(OutputStream out, String path, Map<String, String> h, String body) throws Exception {
        String svcType;
        if (path.startsWith("/ctl/AVTransport")) svcType = AVT;
        else if (path.startsWith("/ctl/RenderingControl")) svcType = RCS;
        else if (path.startsWith("/ctl/ConnectionManager")) svcType = CMS;
        else {
            reply(out, 404, "Not Found", "text/plain", "", null);
            return;
        }
        Map<String, String> args = new HashMap<String, String>();
        String action = parseSoap(body, args);
        if (action == null) {
            reply(out, 400, "Bad Request", "text/plain", "", null);
            return;
        }
        Log.i(TAG, "SOAP " + action + " " + (args.containsKey("CurrentURI") ? args.get("CurrentURI") : ""));
        LinkedHashMap<String, String> res = null;
        int err = 0;
        try {
            if (svcType == AVT) res = doAvt(action, args);
            else if (svcType == RCS) res = doRcs(action, args);
            else res = doCms(action, args);
            if (res == null) err = 401;
        } catch (Exception e) {
            Log.w(TAG, "action " + action, e);
            err = 501;
        }
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\"?>\n<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>");
        if (err != 0) {
            sb.append("<s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>")
                    .append(err).append("</errorCode><errorDescription>")
                    .append(err == 401 ? "Invalid Action" : "Action Failed").append("</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>");
            reply(out, 500, "Internal Server Error", "text/xml; charset=\"utf-8\"", sb.toString(), null);
            return;
        }
        sb.append("<u:").append(action).append("Response xmlns:u=\"").append(svcType).append("\">");
        for (Map.Entry<String, String> e : res.entrySet()) {
            sb.append('<').append(e.getKey()).append('>').append(esc(e.getValue())).append("</").append(e.getKey()).append('>');
        }
        sb.append("</u:").append(action).append("Response></s:Body></s:Envelope>");
        reply(out, 200, "OK", "text/xml; charset=\"utf-8\"", sb.toString(), null);
    }

    private static String parseSoap(String body, Map<String, String> args) {
        try {
            XmlPullParser p = Xml.newPullParser();
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
            p.setInput(new java.io.StringReader(body));
            int depth = 0;
            String action = null;
            int ev;
            while ((ev = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG) {
                    depth++;
                    if (depth == 3) action = p.getName();
                    else if (depth == 4) {
                        String n = p.getName();
                        String t = p.nextText();
                        args.put(n, t == null ? "" : t);
                        depth--;
                    }
                } else if (ev == XmlPullParser.END_TAG) {
                    depth--;
                }
            }
            return action;
        } catch (Exception e) {
            Log.w(TAG, "parseSoap", e);
            return null;
        }
    }

    private static LinkedHashMap<String, String> m() {
        return new LinkedHashMap<String, String>();
    }

    private LinkedHashMap<String, String> doAvt(String a, Map<String, String> in) {
        LinkedHashMap<String, String> r = m();
        if (a.equals("SetAVTransportURI")) {
            final String uri = in.get("CurrentURI") == null ? "" : in.get("CurrentURI").trim();
            curUri = uri;
            curMeta = in.get("CurrentURIMetaData") == null ? "" : in.get("CurrentURIMetaData");
            wantPlay = false;
            final String playUri = uri.startsWith("https://")
                    ? "http://127.0.0.1:" + httpPort + "/proxy?u=" + java.net.URLEncoder.encode(uri)
                    : uri;
            ph.post(new Runnable() {
                public void run() {
                    loadUri(playUri);
                }
            });
            if (uri.length() == 0) transportState = "NO_MEDIA_PRESENT";
            else transportState = "STOPPED";
            fireAvt();
        } else if (a.equals("SetNextAVTransportURI")) {
            nextUri = in.get("NextURI") == null ? "" : in.get("NextURI");
            nextMeta = in.get("NextURIMetaData") == null ? "" : in.get("NextURIMetaData");
        } else if (a.equals("Play")) {
            wantPlay = true;
            ph.post(new Runnable() {
                public void run() {
                    doPlay();
                }
            });
        } else if (a.equals("Pause")) {
            ph.post(new Runnable() {
                public void run() {
                    doPause();
                }
            });
        } else if (a.equals("Stop")) {
            wantPlay = false;
            ph.post(new Runnable() {
                public void run() {
                    doStop();
                }
            });
        } else if (a.equals("Seek")) {
            final int ms = parseTime(in.get("Target"));
            if ("REL_TIME".equals(in.get("Unit")) || "ABS_TIME".equals(in.get("Unit"))) {
                ph.post(new Runnable() {
                    public void run() {
                        if (mp != null && prepared) mp.seekTo(ms);
                    }
                });
            }
        } else if (a.equals("Next") || a.equals("Previous")) {
            // 单曲渲染器: 无操作
        } else if (a.equals("GetTransportInfo")) {
            r.put("CurrentTransportState", transportState);
            r.put("CurrentTransportStatus", "OK");
            r.put("CurrentSpeed", "1");
        } else if (a.equals("GetPositionInfo")) {
            int dur = duration(), pos = position();
            boolean has = curUri.length() > 0;
            r.put("Track", has ? "1" : "0");
            r.put("TrackDuration", fmt(dur));
            r.put("TrackMetaData", curMeta);
            r.put("TrackURI", curUri);
            r.put("RelTime", fmt(pos));
            r.put("AbsTime", fmt(pos));
            r.put("RelCount", "2147483647");
            r.put("AbsCount", "2147483647");
        } else if (a.equals("GetMediaInfo")) {
            r.put("NrTracks", curUri.length() > 0 ? "1" : "0");
            r.put("MediaDuration", fmt(duration()));
            r.put("CurrentURI", curUri);
            r.put("CurrentURIMetaData", curMeta);
            r.put("NextURI", nextUri);
            r.put("NextURIMetaData", nextMeta);
            r.put("PlayMedium", "NETWORK");
            r.put("RecordMedium", "NOT_IMPLEMENTED");
            r.put("WriteStatus", "NOT_IMPLEMENTED");
        } else if (a.equals("GetDeviceCapabilities")) {
            r.put("PlayMedia", "NETWORK");
            r.put("RecMedia", "NOT_IMPLEMENTED");
            r.put("RecQualityModes", "NOT_IMPLEMENTED");
        } else if (a.equals("GetTransportSettings")) {
            r.put("PlayMode", "NORMAL");
            r.put("RecQualityMode", "NOT_IMPLEMENTED");
        } else if (a.equals("GetCurrentTransportActions")) {
            r.put("Actions", "Play,Pause,Stop,Seek");
        } else {
            return null;
        }
        return r;
    }

    private LinkedHashMap<String, String> doRcs(String a, Map<String, String> in) {
        LinkedHashMap<String, String> r = m();
        if (a.equals("GetVolume")) {
            r.put("CurrentVolume", String.valueOf(getVolume()));
        } else if (a.equals("SetVolume")) {
            int v = 0;
            try {
                v = Integer.parseInt(in.get("DesiredVolume").trim());
            } catch (Exception ignored) {
            }
            setVolume(v);
            fireRcs();
        } else if (a.equals("GetMute")) {
            r.put("CurrentMute", mute ? "1" : "0");
        } else if (a.equals("SetMute")) {
            boolean mm = "1".equals(in.get("DesiredMute")) || "true".equalsIgnoreCase(in.get("DesiredMute"));
            if (mm && !mute) {
                preMuteVolume = getVolume();
                applyVolume(0);
            } else if (!mm && mute) {
                applyVolume(preMuteVolume);
            }
            mute = mm;
            fireRcs();
        } else if (a.equals("ListPresets")) {
            r.put("CurrentPresetNameList", "FactoryDefaults");
        } else if (a.equals("SelectPreset")) {
            // 无操作
        } else {
            return null;
        }
        return r;
    }

    private LinkedHashMap<String, String> doCms(String a, Map<String, String> in) {
        LinkedHashMap<String, String> r = m();
        if (a.equals("GetProtocolInfo")) {
            r.put("Source", "");
            r.put("Sink", SINK);
        } else if (a.equals("GetCurrentConnectionIDs")) {
            r.put("ConnectionIDs", "0");
        } else if (a.equals("GetCurrentConnectionInfo")) {
            r.put("RcsID", "0");
            r.put("AVTransportID", "0");
            r.put("ProtocolInfo", "");
            r.put("PeerConnectionManager", "");
            r.put("PeerConnectionID", "-1");
            r.put("Direction", "Input");
            r.put("Status", "OK");
        } else {
            return null;
        }
        return r;
    }

    // =====================================================================
    // 播放器 (均在 player 线程执行)
    private void releasePlayer() {
        prepared = false;
        if (mp != null) {
            try {
                mp.reset();
                mp.release();
            } catch (Exception ignored) {
            }
            mp = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
    }

    private void loadUri(String uri) {
        releasePlayer();
        if (uri.length() == 0) return;
        try {
            final MediaPlayer p = new MediaPlayer();
            mp = p;
            p.setAudioStreamType(AudioManager.STREAM_MUSIC);
            applyVolume(volPct);
            p.setWakeMode(ctx, PowerManager.PARTIAL_WAKE_LOCK);
            p.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                public void onPrepared(MediaPlayer x) {
                    if (x != mp) return;
                    prepared = true;
                    Log.i(TAG, "prepared, duration=" + x.getDuration());
                    if (wantPlay) doPlay();
                    else fireAvt();
                }
            });
            p.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                public void onCompletion(MediaPlayer x) {
                    transportState = "STOPPED";
                    wantPlay = false;
                    fireAvt();
                }
            });
            p.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                public boolean onError(MediaPlayer x, int what, int extra) {
                    Log.e(TAG, "MediaPlayer error " + what + "/" + extra);
                    transportState = "STOPPED";
                    prepared = false;
                    wantPlay = false;
                    fireAvt();
                    return true;
                }
            });
            p.setDataSource(uri);
            transportState = "TRANSITIONING";
            p.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "loadUri failed: " + uri, e);
            transportState = "STOPPED";
        }
    }

    private void doPlay() {
        if (mp == null) return;
        if (!prepared) {
            transportState = "TRANSITIONING";
            return;
        }
        try {
            mp.start();
            transportState = "PLAYING";
        } catch (Exception e) {
            Log.e(TAG, "start failed", e);
        }
        fireAvt();
    }

    private void doPause() {
        try {
            if (mp != null && prepared && mp.isPlaying()) {
                mp.pause();
                transportState = "PAUSED_PLAYBACK";
            }
        } catch (Exception e) {
            Log.e(TAG, "pause", e);
        }
        fireAvt();
    }

    private void doStop() {
        try {
            if (mp != null && prepared) {
                mp.stop();
                prepared = false;
                mp.prepareAsync(); // 保持可再次 Play
            }
        } catch (Exception e) {
            Log.e(TAG, "stop", e);
        }
        transportState = curUri.length() > 0 ? "STOPPED" : "NO_MEDIA_PRESENT";
        fireAvt();
    }

    private int duration() {
        try {
            MediaPlayer p = mp;
            if (p != null && prepared) {
                int d = p.getDuration();
                return d < 0 ? 0 : d;
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private int position() {
        try {
            MediaPlayer p = mp;
            if (p != null && prepared) return p.getCurrentPosition();
        } catch (Exception ignored) {
        }
        return 0;
    }

    private static String fmt(int ms) {
        int s = ms / 1000;
        return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
    }

    private static int parseTime(String t) {
        if (t == null) return 0;
        try {
            String[] p = t.trim().split(":");
            double s = 0;
            for (String x : p) s = s * 60 + Double.parseDouble(x);
            return (int) (s * 1000);
        } catch (Exception e) {
            return 0;
        }
    }

    // 音量: 0..100 <-> STREAM_MUSIC 索引
    // 系统音量级数太粗且低档位仍很响, 所以系统音量固定拉满,
    // 用 MediaPlayer.setVolume 做 0..100 的平方曲线细调。
    private volatile int volPct = 12;

    private int getVolume() {
        return mute ? 0 : volPct;
    }

    private void applyGain() {
        MediaPlayer p = mp;
        if (p == null) return;
        float g = volPct / 100f;
        g = g * g;
        try {
            p.setVolume(g, g);
        } catch (Throwable t) {
            Log.w(TAG, "setVolume " + t);
        }
    }

    private void setVolume(int pct) {
        pct = Math.max(0, Math.min(100, pct));
        if (mute) mute = false;
        applyVolume(pct);
    }

    private void applyVolume(int pct) {
        AudioManager am = (AudioManager) ctx.getSystemService(Context.AUDIO_SERVICE);
        int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        if (am.getStreamVolume(AudioManager.STREAM_MUSIC) != max)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, max, 0);
        volPct = pct;
        applyGain();
    }

    // =====================================================================
    // GENA 事件
    private void handleSubscribe(OutputStream out, String path, Map<String, String> h) throws Exception {
        String service = path.contains("AVTransport") ? "AVTransport"
                : path.contains("RenderingControl") ? "RenderingControl"
                : path.contains("ConnectionManager") ? "ConnectionManager" : null;
        if (service == null) {
            reply(out, 404, "Not Found", "text/plain", "", null);
            return;
        }
        String sid = h.get("sid");
        String cb = h.get("callback");
        Sub target = null;
        synchronized (subs) {
            if (sid != null) {
                for (Sub s : subs) if (s.sid.equals(sid)) target = s;
                if (target == null) {
                    reply(out, 412, "Precondition Failed", "text/plain", "", null);
                    return;
                }
            } else {
                if (cb == null) {
                    reply(out, 412, "Precondition Failed", "text/plain", "", null);
                    return;
                }
                target = new Sub();
                target.sid = "uuid:" + UUID.randomUUID().toString();
                target.service = service;
                int a = cb.indexOf('<'), b = cb.indexOf('>');
                target.callback = (a >= 0 && b > a) ? cb.substring(a + 1, b) : cb;
                subs.add(target);
            }
            target.expire = System.currentTimeMillis() + 1800 * 1000L;
        }
        reply(out, 200, "OK", "text/plain", "", "SID: " + target.sid + "\r\nTIMEOUT: Second-1800\r\n");
        out.flush();
        if (sid == null) {
            final Sub t = target;
            pool.execute(new Runnable() {
                public void run() {
                    sleep(300);
                    sendEvent(t);
                }
            });
        }
    }

    private void expireSubs() {
        long now = System.currentTimeMillis();
        synchronized (subs) {
            for (int i = subs.size() - 1; i >= 0; i--) if (subs.get(i).expire < now) subs.remove(i);
        }
    }

    private void fireAvt() {
        fire("AVTransport");
    }

    private void fireRcs() {
        fire("RenderingControl");
    }

    private void fire(final String service) {
        final List<Sub> l = new ArrayList<Sub>();
        synchronized (subs) {
            for (Sub s : subs) if (s.service.equals(service)) l.add(s);
        }
        for (final Sub s : l) {
            pool.execute(new Runnable() {
                public void run() {
                    sendEvent(s);
                }
            });
        }
    }

    private static String val(String n, String v) {
        return "<" + n + " val=\"" + esc(v) + "\"/>";
    }

    private void sendEvent(Sub s) {
        try {
            String inner;
            if (s.service.equals("AVTransport")) {
                inner = "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/AVT/\"><InstanceID val=\"0\">"
                        + val("TransportState", transportState) + val("TransportStatus", "OK")
                        + val("CurrentTransportActions", "Play,Pause,Stop,Seek")
                        + val("AVTransportURI", curUri) + val("CurrentTrackURI", curUri)
                        + val("AVTransportURIMetaData", curMeta)
                        + val("CurrentTrackDuration", fmt(duration())) + val("CurrentMediaDuration", fmt(duration()))
                        + "</InstanceID></Event>";
            } else if (s.service.equals("RenderingControl")) {
                inner = "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/RCS/\"><InstanceID val=\"0\">"
                        + "<Volume val=\"" + getVolume() + "\" channel=\"Master\"/>"
                        + "<Mute val=\"" + (mute ? 1 : 0) + "\" channel=\"Master\"/></InstanceID></Event>";
            } else {
                String body = "<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\"><e:property><SourceProtocolInfo></SourceProtocolInfo></e:property>"
                        + "<e:property><SinkProtocolInfo>" + esc(SINK) + "</SinkProtocolInfo></e:property>"
                        + "<e:property><CurrentConnectionIDs>0</CurrentConnectionIDs></e:property></e:propertyset>";
                post(s, body);
                return;
            }
            String body = "<e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\"><e:property><LastChange>"
                    + esc(inner) + "</LastChange></e:property></e:propertyset>";
            post(s, body);
        } catch (Exception e) {
            Log.w(TAG, "sendEvent: " + e);
        }
    }

    private void post(Sub s, String body) throws Exception {
        java.net.URL u = new java.net.URL(s.callback);
        int port = u.getPort() > 0 ? u.getPort() : 80;
        Socket c = new Socket();
        try {
            c.connect(new InetSocketAddress(u.getHost(), port), 3000);
            c.setSoTimeout(3000);
            byte[] b = body.getBytes("UTF-8");
            String path = u.getFile().length() == 0 ? "/" : u.getFile();
            String hd = "NOTIFY " + path + " HTTP/1.1\r\nHOST: " + u.getHost() + ":" + port
                    + "\r\nCONTENT-TYPE: text/xml; charset=\"utf-8\"\r\nNT: upnp:event\r\nNTS: upnp:propchange\r\nSID: "
                    + s.sid + "\r\nSEQ: " + (s.seq++) + "\r\nCONTENT-LENGTH: " + b.length + "\r\nCONNECTION: close\r\n\r\n";
            OutputStream o = c.getOutputStream();
            o.write(hd.getBytes("UTF-8"));
            o.write(b);
            o.flush();
            InputStream i = c.getInputStream();
            byte[] tmp = new byte[256];
            i.read(tmp);
        } finally {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
    }

    // =====================================================================
    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                default: sb.append(c);
            }
        }
        return sb.toString();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }
}
