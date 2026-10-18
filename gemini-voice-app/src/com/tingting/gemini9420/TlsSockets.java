package com.tingting.gemini9420;

import android.util.Log;

import java.net.InetAddress;
import java.net.Socket;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

final class TlsSockets {
    private static final String TAG = "TlsSockets";
    private static SSLSocketFactory factory;

    private TlsSockets() {
    }

    static synchronized SSLSocketFactory factory() throws Exception {
        if (factory != null) return factory;
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
        factory = new SSLSocketFactory() {
            private Socket tune(Socket s) {
                if (s instanceof SSLSocket) {
                    SSLSocket ss = (SSLSocket) s;
                    ss.setEnabledProtocols(new String[]{"TLSv1.2", "TLSv1.1", "TLSv1"});
                    ss.setEnabledCipherSuites(ss.getSupportedCipherSuites());
                }
                return s;
            }

            private Socket tuneHost(Socket s, String host) {
                tune(s);
                try {
                    s.getClass().getMethod("setHostname", String.class).invoke(s, host);
                } catch (Throwable t) {
                    Log.w(TAG, "sni " + t);
                }
                return s;
            }

            public String[] getDefaultCipherSuites() {
                return base.getDefaultCipherSuites();
            }

            public String[] getSupportedCipherSuites() {
                return base.getSupportedCipherSuites();
            }

            public Socket createSocket(Socket s, String h, int p, boolean a) throws java.io.IOException {
                return tuneHost(base.createSocket(s, h, p, a), h);
            }

            public Socket createSocket(String h, int p) throws java.io.IOException {
                return tuneHost(base.createSocket(h, p), h);
            }

            public Socket createSocket(String h, int p, InetAddress la, int lp) throws java.io.IOException {
                return tuneHost(base.createSocket(h, p, la, lp), h);
            }

            public Socket createSocket(InetAddress h, int p) throws java.io.IOException {
                return tune(base.createSocket(h, p));
            }

            public Socket createSocket(InetAddress h, int p, InetAddress la, int lp) throws java.io.IOException {
                return tune(base.createSocket(h, p, la, lp));
            }
        };
        return factory;
    }
}
