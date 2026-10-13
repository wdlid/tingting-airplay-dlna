#!/bin/bash
# 用法: addr.sh 0xPC [0xLR ...]  -> 用未剥离的 nopie 二进制解析地址
cd "$(dirname "$0")"
export PATH="$HOME/sp/toolchain/arm-linux-musleabihf-cross/bin:$PATH"
cp "$HOME/sp/src/shairport-sync-4.3.7/shairport-sync" dist/shairport-sync-nopie.unstripped
arm-linux-musleabihf-addr2line -f -C -i -e dist/shairport-sync-nopie.unstripped "$@"
