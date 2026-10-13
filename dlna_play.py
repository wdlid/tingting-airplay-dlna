"""向平板上的 DLNA 渲染器推一个 URL 并轮询状态. 用法: python dlna_play.py <url> [秒数]"""
import sys, time, re
sys.argv_backup = sys.argv
import dlna_test as t

url = sys.argv_backup[1]
secs = int(sys.argv_backup[2]) if len(sys.argv_backup) > 2 else 12
b = "http://%s:49152" % t.DEVICE_IP
print(t.soap(b, t.AV, "SetAVTransportURI",
             "<InstanceID>0</InstanceID><CurrentURI>%s</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>"
             % url.replace("&", "&amp;"))[-90:])
t.soap(b, t.AV, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
for _ in range(secs):
    time.sleep(1.5)
    r = t.soap(b, t.AV, "GetTransportInfo", "<InstanceID>0</InstanceID>")
    p = t.soap(b, t.AV, "GetPositionInfo", "<InstanceID>0</InstanceID>")
    print(re.findall(r"<CurrentTransportState>(.*?)<", r), re.findall(r"<RelTime>(.*?)<", p),
          re.findall(r"<TrackDuration>(.*?)<", p))
