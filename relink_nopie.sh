#!/bin/bash
# 备用: 重新链接为传统静态 ET_EXEC (非 PIE), 以防旧内核不兼容 static-PIE
set -e
PROJ="$(cd "$(dirname "$0")" && pwd)"
export PATH="$HOME/sp/toolchain/arm-linux-musleabihf-cross/bin:$PATH"
cd "$HOME/sp/src/shairport-sync-4.3.7"
cp "$PROJ/alsa_embed.inc" .
bash "$PROJ/patch_alsa.sh" audio_alsa.c
touch shairport.c audio_alsa.c
rm -f shairport-sync
make LDFLAGS="-L$HOME/sp/prefix/lib -static -no-pie"
arm-linux-musleabihf-strip -o "$PROJ/dist/shairport-sync-nopie" shairport-sync
chmod +x "$PROJ/dist/shairport-sync-nopie"
file "$PROJ/dist/shairport-sync-nopie"
qemu-arm-static "$PROJ/dist/shairport-sync-nopie" -V
