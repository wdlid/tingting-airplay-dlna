#!/bin/bash
# 解析系统音频 HAL 里的 pcm_config_mm_out (tinyalsa pcm_config)
cd "$(dirname "$0")"
T="$HOME/sp/toolchain/arm-linux-musleabihf-cross/bin/arm-linux-musleabihf"
"$T-readelf" -s hal.so | grep -E 'pcm_config'
for s in $("$T-readelf" -s hal.so | awk '/pcm_config/ {print $2":"$3}'); do
  addr=${s%%:*}; size=${s##*:}
  echo "== $s"
  "$T-objdump" -s --start-address=0x$addr --stop-address=$((0x$addr + size)) hal.so | tail -n +5
done
