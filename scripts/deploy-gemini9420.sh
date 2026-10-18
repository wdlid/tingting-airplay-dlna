#!/bin/bash
# 快速部署 Gemini / Adapter（避免重复 push 66MB 原厂包）
# 用法:
#   ./scripts/deploy-gemini9420.sh gemini          # 日常改语音 APK（默认）
#   ./scripts/deploy-gemini9420.sh adapter         # 仅 adapter（dex 补丁后）
#   ./scripts/deploy-gemini9420.sh adapter-install # 设备上已有 apk，只刷 /system
#   ./scripts/deploy-gemini9420.sh all             # gemini + adapter
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEVICE="${DEVICE:-192.168.2.224:5555}"
if [ -z "${ADB_BIN:-}" ]; then
  if command -v adb >/dev/null 2>&1; then
    ADB_BIN=adb
  else
    for c in /mnt/c/Users/*/AppData/Local/Android/Sdk/platform-tools/adb.exe; do
      if [ -x "$c" ]; then ADB_BIN="$c"; break; fi
    done
  fi
fi
[ -n "${ADB_BIN:-}" ] || { echo "adb not found (install SDK platform-tools or set ADB_BIN)" >&2; exit 1; }
ADB=("$ADB_BIN" -s "$DEVICE")
PUSH="$ROOT/scripts/adb-push-smart.sh"
MODE="${1:-gemini}"

connect() {
  "${ADB[@]}" get-state >/dev/null 2>&1 || adb connect "$DEVICE" >/dev/null 2>&1 || true
  "${ADB[@]}" root >/dev/null 2>&1 || true
  "${ADB[@]}" get-state >/dev/null
}

build_gemini() {
  echo "== build GeminiVoice9420 =="
  bash "$ROOT/gemini-voice-app/build-apk.sh"
}

build_adapter() {
  echo "== build patched adapter =="
  bash "$ROOT/adapter-mod/build-patched-apk.sh"
}

deploy_gemini() {
  build_gemini
  APK="$ROOT/dist/GeminiVoice9420.apk"
  LM=$(md5sum "$APK" | awk '{print $1}')
  RM=$("${ADB[@]}" shell "busybox md5sum /data/local/tmp/GeminiVoice9420.apk 2>/dev/null" | awk '{print $1}' || true)
  if [ -n "$RM" ] && [ "$LM" = "$RM" ]; then
    echo "[skip] GeminiVoice9420.apk unchanged (skip adb transfer)"
    "${ADB[@]}" shell pm install -r /data/local/tmp/GeminiVoice9420.apk >/dev/null || \
      "${ADB[@]}" install -r "$APK"
  else
    echo "[install] GeminiVoice9420.apk"
    "${ADB[@]}" install -r "$APK"
    "${ADB[@]}" push "$APK" /data/local/tmp/GeminiVoice9420.apk >/dev/null
  fi
  echo "[ok] gemini deployed"
}

deploy_adapter() {
  build_adapter
  OUT="$ROOT/adapter-mod/out/miaAdapter-patched-keepcert.apk"
  INSTALL_SH="$ROOT/adapter-mod/install-patched-adapter.sh"
  bash "$PUSH" "$OUT" /data/local/tmp/miaAdapter-patched-keepcert.apk 524288
  ADB_NO_GZIP=1 bash "$PUSH" "$INSTALL_SH" /data/local/tmp/install-patched-adapter.sh 999999999
  "${ADB[@]}" shell sh /data/local/tmp/install-patched-adapter.sh
  echo "[ok] adapter on /system (reboot if dalvik cache was cleared)"
}

adapter_install_only() {
  INSTALL_SH="$ROOT/adapter-mod/install-patched-adapter.sh"
  ADB_NO_GZIP=1 bash "$PUSH" "$INSTALL_SH" /data/local/tmp/install-patched-adapter.sh 999999999
  "${ADB[@]}" shell "[ -f /data/local/tmp/miaAdapter-patched-keepcert.apk ] || exit 3"
  "${ADB[@]}" shell sh /data/local/tmp/install-patched-adapter.sh
  echo "[ok] adapter install from device cache only"
}

connect

case "$MODE" in
  gemini|g)
    deploy_gemini
    ;;
  adapter|a)
    deploy_adapter
    ;;
  adapter-install|ai)
    adapter_install_only
    ;;
  all)
    deploy_gemini
    deploy_adapter
    ;;
  *)
    echo "unknown mode: $MODE (gemini|adapter|adapter-install|all)" >&2
    exit 1
    ;;
esac
