#!/bin/bash
# 仅在文件变更时 adb push；大文件默认 gzip 压缩传输（设备端 busybox gunzip）。
# 用法: DEVICE=192.168.2.224:5555 ./adb-push-smart.sh <local> <remote> [size_threshold_for_gzip]
set -euo pipefail

LOCAL=${1:?local path}
REMOTE=${2:?remote path}
THRESH=${3:-1048576}

DEVICE="${DEVICE:-192.168.2.224:5555}"
if [ -z "${ADB_BIN:-}" ]; then
  if command -v adb >/dev/null 2>&1; then ADB_BIN=adb
  else
    for c in /mnt/c/Users/*/AppData/Local/Android/Sdk/platform-tools/adb.exe; do
      if [ -x "$c" ]; then ADB_BIN="$c"; break; fi
    done
  fi
fi
[ -n "${ADB_BIN:-}" ] || { echo "adb not found" >&2; exit 1; }
ADB=("$ADB_BIN" -s "$DEVICE")

if [ ! -f "$LOCAL" ]; then
  echo "[fail] missing local file: $LOCAL" >&2
  exit 1
fi

local_md5() {
  if command -v md5sum >/dev/null 2>&1; then
    md5sum "$1" | awk '{print $1}'
  else
    md5 -q "$1"
  fi
}

remote_md5() {
  "${ADB[@]}" shell "busybox md5sum '$REMOTE' 2>/dev/null" | awk '{print $1}'
}

LM=$(local_md5 "$LOCAL")
RM=$(remote_md5 || true)
if [ -n "$RM" ] && [ "$LM" = "$RM" ]; then
  echo "[skip] unchanged $(basename "$LOCAL") -> $REMOTE"
  exit 0
fi

SIZE=$(wc -c <"$LOCAL" | tr -d ' ')
USE_GZIP=0
if [ "$SIZE" -gt "$THRESH" ] && [ "${ADB_NO_GZIP:-0}" != 1 ]; then
  USE_GZIP=1
fi

if [ "$USE_GZIP" = 1 ]; then
  TMPGZ=$(mktemp "${TMPDIR:-/tmp}/adbpush.XXXXXX.gz")
  trap 'rm -f "$TMPGZ"' EXIT
  gzip -c -9 "$LOCAL" >"$TMPGZ"
  GZSIZE=$(wc -c <"$TMPGZ" | tr -d ' ')
  echo "[push] gzip $SIZE -> $GZSIZE bytes $(basename "$LOCAL")"
  REMOTE_GZ="${REMOTE}.gz"
  "${ADB[@]}" push "$TMPGZ" "$REMOTE_GZ"
  "${ADB[@]}" shell "busybox gzip -df '$REMOTE_GZ'"
else
  echo "[push] $SIZE bytes $(basename "$LOCAL") -> $REMOTE"
  "${ADB[@]}" push "$LOCAL" "$REMOTE"
fi

RM2=$(remote_md5 || true)
if [ "$LM" != "$RM2" ]; then
  echo "[warn] md5 mismatch after push local=$LM remote=$RM2" >&2
  exit 2
fi
echo "[done] $REMOTE ($LM)"
