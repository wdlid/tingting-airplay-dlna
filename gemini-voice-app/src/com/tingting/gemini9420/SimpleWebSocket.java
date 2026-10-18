package com.tingting.gemini9420;

import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.security.SecureRandom;
import javax.net.ssl.SSLSocketFactory;

final class SimpleWebSocket {
    private final String tcpHost;
    private final int tcpPort;
    private final String tlsHost;
    private final String path;
    private final int connectTimeoutMs;
    private Socket socket;
    private InputStream in;
    private OutputStream out;
    private final SecureRandom rnd = new SecureRandom();

    SimpleWebSocket(String tcpHost, int tcpPort, String tlsHost, String path, int connectTimeoutMs) {
        this.tcpHost = tcpHost;
        this.tcpPort = tcpPort;
        this.tlsHost = tlsHost;
        this.path = path;
        this.connectTimeoutMs = connectTimeoutMs;
    }

    void connect() throws Exception {
        SSLSocketFactory sf = TlsSockets.factory();
        Socket raw = new Socket();
        raw.connect(new java.net.InetSocketAddress(tcpHost, tcpPort), connectTimeoutMs);
        socket = sf.createSocket(raw, tlsHost, tcpPort, true);
        socket.setSoTimeout(600000);
        socket.setTcpNoDelay(true);
        in = socket.getInputStream();
        out = socket.getOutputStream();
        byte[] key = new byte[16];
        rnd.nextBytes(key);
        String secKey = Base64.encodeToString(key, Base64.NO_WRAP);
        String req = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + tlsHost + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "Sec-WebSocket-Key: " + secKey + "\r\n"
                + "\r\n";
        out.write(req.getBytes("UTF-8"));
        out.flush();
        String status = readLine();
        StringBuilder hdr = new StringBuilder();
        String line;
        while ((line = readLine()) != null && line.length() > 0) {
            hdr.append(line).append('\n');
        }
        if (status == null || !status.contains("101")) {
            throw new Exception("ws handshake failed: " + status + " headers=" + hdr);
        }
    }

    void sendText(String text) throws Exception {
        byte[] data = text.getBytes("UTF-8");
        sendFrame((byte) 0x81, data);
    }

    String receiveText() throws Exception {
        while (true) {
            int b0 = in.read();
            if (b0 < 0) return null;
            int b1 = in.read();
            if (b1 < 0) return null;
            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0f;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7f;
            if (len == 126) {
                len = ((in.read() & 0xff) << 8) | (in.read() & 0xff);
            } else if (len == 127) {
                for (int i = 0; i < 8; i++) in.read();
                len = 0;
            }
            byte[] mask = null;
            if (masked) {
                mask = new byte[4];
                readFully(mask);
            }
            byte[] payload = new byte[(int) len];
            readFully(payload);
            if (masked) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= mask[i % 4];
                }
            }
            if (opcode == 0x8) return null;
            if (opcode == 0x9) {
                sendFrame((byte) 0x8A, payload);
                continue;
            }
            if (opcode == 0xA) continue;
            if (opcode == 0x1 || opcode == 0x2 || opcode == 0x0) {
                if (!fin) {
                    ByteArrayOutputStream acc = new ByteArrayOutputStream();
                    acc.write(payload);
                    while (true) {
                        int h0 = in.read();
                        int h1 = in.read();
                        if (h0 < 0) break;
                        boolean fin2 = (h0 & 0x80) != 0;
                        int op2 = h0 & 0x0f;
                        boolean m2 = (h1 & 0x80) != 0;
                        long l2 = h1 & 0x7f;
                        if (l2 == 126) {
                            l2 = ((in.read() & 0xff) << 8) | (in.read() & 0xff);
                        } else if (l2 == 127) {
                            for (int i = 0; i < 8; i++) in.read();
                            l2 = 0;
                        }
                        if (m2) {
                            byte[] mk = new byte[4];
                            readFully(mk);
                        }
                        byte[] pl = new byte[(int) l2];
                        readFully(pl);
                        acc.write(pl);
                        if (fin2) break;
                        if (op2 != 0x0) break;
                    }
                    return new String(acc.toByteArray(), "UTF-8");
                }
                return new String(payload, "UTF-8");
            }
        }
    }

    void close() {
        try {
            if (out != null) sendFrame((byte) 0x88, new byte[0]);
        } catch (Exception ignored) {
        }
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {
        }
    }

    private void sendFrame(byte opcode, byte[] data) throws Exception {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(opcode);
        byte[] mask = new byte[4];
        rnd.nextBytes(mask);
        if (data.length < 126) {
            frame.write(0x80 | data.length);
        } else if (data.length <= 0xffff) {
            frame.write(0x80 | 126);
            frame.write((data.length >> 8) & 0xff);
            frame.write(data.length & 0xff);
        } else {
            throw new Exception("frame too large");
        }
        frame.write(mask);
        byte[] masked = new byte[data.length];
        for (int i = 0; i < data.length; i++) masked[i] = (byte) (data[i] ^ mask[i % 4]);
        frame.write(masked);
        out.write(frame.toByteArray());
        out.flush();
    }

    private void readFully(byte[] buf) throws Exception {
        int off = 0;
        while (off < buf.length) {
            int r = in.read(buf, off, buf.length - off);
            if (r < 0) throw new Exception("eof");
            off += r;
        }
    }

    private String readLine() throws Exception {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            if (c != '\r') line.write(c);
        }
        if (line.size() == 0 && c < 0) return null;
        return line.toString("UTF-8");
    }
}
