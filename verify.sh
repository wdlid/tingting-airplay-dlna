#!/bin/bash
# 检查产物: 是否纯静态、架构属性, 并用 qemu 做冒烟测试
BIN="$(cd "$(dirname "$0")" && pwd)/dist/shairport-sync"
RE="$HOME/sp/toolchain/arm-linux-musleabihf-cross/bin/arm-linux-musleabihf-readelf"
file "$BIN"
echo "--- program headers / dynamic"
"$RE" -l -d "$BIN" | grep -E 'Type:|INTERP|NEEDED|Shared library' || echo "(无 INTERP / NEEDED => 纯静态)"
echo "--- arch attrs"
"$RE" -A "$BIN" | grep -E 'Tag_CPU_arch:|Tag_FP_arch|Tag_Advanced_SIMD|Tag_ABI_VFP_args'
if command -v qemu-arm-static >/dev/null; then
  echo "--- qemu -V"
  qemu-arm-static "$BIN" -V
fi
