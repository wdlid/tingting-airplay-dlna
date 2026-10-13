"""DLNA 渲染器自测: SSDP 发现 -> 取描述 -> SOAP 控制 (在 Windows 上运行).
用法: python dlna_test.py [wav|url]
 - 默认生成 5 秒钟的 440Hz+880Hz 测试 WAV 并在本机起 HTTP 服务, 让平板去拉取播放
"""
import socket, struct, sys, time, threading, http.server, socketserver, math, wave, io, re
import urllib.request

import os
DEVICE_IP = os.environ.get("DEVICE_IP", "192.168.2.224")
AV = "urn:schemas-upnp-org:service:AVTransport:1"
RC = "urn:schemas-upnp-org:service:RenderingControl:1"


def local_ip(target):
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.connect((target, 9))
    ip = s.getsockname()[0]
    s.close()
    return ip


def discover(timeout=4):
    msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\n"
           "ST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n").encode()
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.settimeout(1)
    # 多网卡(VPN/虚拟网卡)时必须指定走局域网网卡发组播
    s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_IF, socket.inet_aton(local_ip(DEVICE_IP)))
    s.sendto(msg, ("239.255.255.250", 1900))
    end = time.time() + timeout
    found = {}
    while time.time() < end:
        try:
            data, addr = s.recvfrom(4096)
        except socket.timeout:
            continue
        m = re.search(r"LOCATION:\s*(\S+)", data.decode(errors="ignore"), re.I)
        if m:
            found[addr[0]] = m.group(1)
    return found


def soap(base, svc, action, body=""):
    path = {"AVTransport": "/ctl/AVTransport", "RenderingControl": "/ctl/RenderingControl"}
    stype = AV if "Transport" in action or action in ("Play", "Stop", "Pause", "Seek") else RC
    stype = svc
    name = "AVTransport" if svc == AV else "RenderingControl"
    xml = ('<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
           's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>'
           '<u:%s xmlns:u="%s">%s</u:%s></s:Body></s:Envelope>' % (action, stype, body, action))
    req = urllib.request.Request(base + path[name], xml.encode(), {
        "Content-Type": 'text/xml; charset="utf-8"', "SOAPACTION": '"%s#%s"' % (stype, action)})
    try:
        return urllib.request.urlopen(req, timeout=10).read().decode()
    except urllib.error.HTTPError as e:
        return "HTTP %d %s" % (e.code, e.read().decode())


def make_wav(path, secs=6):
    with wave.open(path, "wb") as w:
        w.setnchannels(2); w.setsampwidth(2); w.setframerate(44100)
        frames = bytearray()
        for i in range(44100 * secs):
            v = 0.4 * (math.sin(2 * math.pi * 440 * i / 44100) + math.sin(2 * math.pi * 660 * i / 44100)) / 2
            s = int(v * 32767)
            frames += struct.pack("<hh", s, s)
        w.writeframes(bytes(frames))


if __name__ == "__main__":
    devs = discover()
    print("发现设备:", devs)
    if not devs:
        sys.exit(1)
    ip, loc = next(iter(devs.items()))
    base = "http://%s:%s" % (ip, loc.split("//")[1].split("/")[0].split(":")[1])
    print(urllib.request.urlopen(loc, timeout=5).read().decode()[:300], "...\n")

    if len(sys.argv) > 1 and sys.argv[1].startswith("http"):
        url = sys.argv[1]
    else:
        make_wav("test.wav")
        port = 8765
        handler = http.server.SimpleHTTPRequestHandler
        socketserver.TCPServer.allow_reuse_address = True
        httpd = socketserver.TCPServer(("", port), handler)
        threading.Thread(target=httpd.serve_forever, daemon=True).start()
        url = "http://%s:%d/test.wav" % (local_ip(ip), port)
    print("播放:", url)
    print(soap(base, AV, "SetAVTransportURI", "<InstanceID>0</InstanceID><CurrentURI>%s</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>" % url)[:200])
    print(soap(base, AV, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")[:200])
    for _ in range(4):
        time.sleep(1.5)
        r = soap(base, AV, "GetTransportInfo", "<InstanceID>0</InstanceID>")
        p = soap(base, AV, "GetPositionInfo", "<InstanceID>0</InstanceID>")
        print(re.findall(r"<CurrentTransportState>(.*?)<", r), re.findall(r"<RelTime>(.*?)<", p), re.findall(r"<TrackDuration>(.*?)<", p))
    print("音量:", re.findall(r"<CurrentVolume>(.*?)<", soap(base, RC, "GetVolume", "<InstanceID>0</InstanceID><Channel>Master</Channel>")))
    time.sleep(3)
    print(soap(base, AV, "Stop", "<InstanceID>0</InstanceID>")[:120])
