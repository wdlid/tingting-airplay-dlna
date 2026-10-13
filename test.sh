#!/bin/bash
# 设备端冒烟测试 (在 PC 上运行, 需要 adb 已连上设备)
# 用法: DEVICE=192.168.2.224:5555 ./test.sh
set -u
DEVICE="${DEVICE:-192.168.2.224:5555}"
IP="${DEVICE%%:*}"
A="adb -s $DEVICE"
ok() { echo "[ OK ] $*"; }
bad() { echo "[FAIL] $*"; FAIL=1; }
FAIL=0

$A get-state >/dev/null 2>&1 && ok "adb 已连接 $DEVICE" || { bad "adb 未连接"; exit 1; }
$A shell pm list packages | grep -q com.tingting.shairport && ok "APK 已安装" || bad "APK 未安装"
$A shell ps | grep -q shairport-sync && ok "shairport-sync 进程在运行" || bad "shairport-sync 未运行"
$A shell cat /proc/net/tcp | grep -qi ':1388 ' && ok "AirPlay RTSP 端口 5000 监听中" || bad "端口 5000 未监听"
$A shell cat /proc/net/tcp | grep -qi ':BF00 ' && ok "DLNA HTTP 端口 49152 监听中" || bad "端口 49152 未监听"
$A shell cat /proc/net/udp | grep -qi ':076C ' && ok "SSDP 端口 1900 监听中" || bad "端口 1900 未监听"

echo "--- DLNA SSDP 发现 + 本地 WAV 播放 (python dlna_test.py) ---"
DEVICE_IP="$IP" python3 dlna_test.py && ok "DLNA 测试脚本完成" || bad "DLNA 测试失败"
echo "(需要自己再验证: iPhone 控制中心 AirPlay 选择 TingTing 播放; 网易云/QQ音乐投屏)"
exit $FAIL
