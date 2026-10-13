#!/bin/bash
# 在 WSL(Ubuntu) 中用 musl 交叉工具链静态编译 shairport-sync (AirPlay 1)
# 目标: ARMv7-A hard-float (NEON/VFPv4), Linux 3.4, 完全静态, 零动态依赖
set -euo pipefail

PROJ="$(cd "$(dirname "$0")" && pwd)"
WORK="${WORK:-$HOME/sp}"
DL="$WORK/dl"; SRC="$WORK/src"; PREFIX="$WORK/prefix"; TCDIR="$WORK/toolchain"
JOBS="$(nproc)"
CROSS=arm-linux-musleabihf

SP_VER=4.3.7; ALSA_VER=1.2.10; SSL_VER=3.0.15; POPT_VER=1.19; LIBCONF_VER=1.7.3

mkdir -p "$DL" "$SRC" "$PREFIX" "$TCDIR"

fetch() { # url file
  [ -s "$DL/$2" ] || curl -fL --retry 3 -o "$DL/$2" "$1"
}
unpack() { # file -> srcdir (clean)
  local dir="$SRC/$2"; rm -rf "$dir"; tar xf "$DL/$1" -C "$SRC"
}

echo "==> 下载"
fetch https://musl.cc/arm-linux-musleabihf-cross.tgz toolchain.tgz
fetch https://www.alsa-project.org/files/pub/lib/alsa-lib-$ALSA_VER.tar.bz2 alsa-lib-$ALSA_VER.tar.bz2
fetch https://github.com/openssl/openssl/releases/download/openssl-$SSL_VER/openssl-$SSL_VER.tar.gz openssl-$SSL_VER.tar.gz
fetch http://ftp.rpm.org/popt/releases/popt-1.x/popt-$POPT_VER.tar.gz popt-$POPT_VER.tar.gz
fetch https://github.com/hyperrealm/libconfig/releases/download/v$LIBCONF_VER/libconfig-$LIBCONF_VER.tar.gz libconfig-$LIBCONF_VER.tar.gz
fetch https://github.com/mikebrady/shairport-sync/archive/refs/tags/$SP_VER.tar.gz shairport-sync-$SP_VER.tar.gz

[ -x "$TCDIR/$CROSS-cross/bin/$CROSS-gcc" ] || tar xf "$DL/toolchain.tgz" -C "$TCDIR"
export PATH="$TCDIR/$CROSS-cross/bin:$PATH"

export CC="$CROSS-gcc" AR="$CROSS-ar" RANLIB="$CROSS-ranlib" STRIP="$CROSS-strip"
ARCH="-march=armv7-a -mfpu=neon-vfpv4 -mfloat-abi=hard -mtune=cortex-a7"
export CFLAGS="$ARCH -O2 -I$PREFIX/include"
export CPPFLAGS="-I$PREFIX/include"
export LDFLAGS="-L$PREFIX/lib -static"
export PKG_CONFIG_PATH="" PKG_CONFIG_LIBDIR=""

echo "==> OpenSSL $SSL_VER"
if [ ! -f "$PREFIX/lib/libssl.a" ]; then
  unpack openssl-$SSL_VER.tar.gz openssl-$SSL_VER
  ( cd "$SRC/openssl-$SSL_VER"
    # OpenSSL 自己会拼接交叉前缀, 不能带着 CC/AR 等环境变量
    unset CC AR RANLIB STRIP CFLAGS CPPFLAGS LDFLAGS
    ./Configure linux-armv4 --cross-compile-prefix=$CROSS- --prefix="$PREFIX" --libdir=lib \
      no-shared no-asm no-async no-dso no-engine no-tests no-comp no-ui-console \
      $ARCH -O2
    make -j"$JOBS" build_libs
    make install_dev )
fi

echo "==> alsa-lib $ALSA_VER"
if [ ! -f "$PREFIX/lib/libasound.a" ]; then
  unpack alsa-lib-$ALSA_VER.tar.bz2 alsa-lib-$ALSA_VER
  ( cd "$SRC/alsa-lib-$ALSA_VER"
    ./configure --host=$CROSS --prefix="$PREFIX" --disable-shared --enable-static \
      --disable-python --disable-topology --disable-ucm --disable-alisp \
      --disable-seq --disable-rawmidi --disable-hwdep --disable-old-symbols \
      --without-debug
    make -j"$JOBS"
    make install )
fi

echo "==> popt $POPT_VER"
if [ ! -f "$PREFIX/lib/libpopt.a" ]; then
  unpack popt-$POPT_VER.tar.gz popt-$POPT_VER
  ( cd "$SRC/popt-$POPT_VER"
    ./configure --host=$CROSS --prefix="$PREFIX" --disable-shared --enable-static --disable-nls
    make -j"$JOBS" -C src libpopt.la
    make -C src install-libLTLIBRARIES install-includeHEADERS || make install )
fi

echo "==> libconfig $LIBCONF_VER"
if [ ! -f "$PREFIX/lib/libconfig.a" ]; then
  unpack libconfig-$LIBCONF_VER.tar.gz libconfig-$LIBCONF_VER
  ( cd "$SRC/libconfig-$LIBCONF_VER"
    ./configure --host=$CROSS --prefix="$PREFIX" --disable-shared --enable-static \
      --disable-examples --disable-tests --disable-cxx
    make -j"$JOBS" -C lib
    make -C lib install )
fi

echo "==> libsoxr 0.1.3 (声卡固定 48kHz, 需要 44.1k->48k 重采样)"
if [ ! -f "$PREFIX/lib/libsoxr.a" ]; then
  fetch https://github.com/chirlu/soxr/archive/refs/tags/0.1.3.tar.gz soxr-0.1.3.tar.gz
  rm -rf "$SRC/soxr-0.1.3"; tar xf "$DL/soxr-0.1.3.tar.gz" -C "$SRC"
  ( cd "$SRC/soxr-0.1.3"
    unset CFLAGS CPPFLAGS LDFLAGS
    mkdir -p build && cd build
    cmake .. -DCMAKE_SYSTEM_NAME=Linux -DCMAKE_SYSTEM_PROCESSOR=arm \
      -DCMAKE_C_COMPILER=$CROSS-gcc -DCMAKE_AR=$(which $CROSS-ar) -DCMAKE_RANLIB=$(which $CROSS-ranlib) \
      -DCMAKE_C_FLAGS="$ARCH -O2" -DCMAKE_BUILD_TYPE=Release \
      -DCMAKE_INSTALL_PREFIX="$PREFIX" -DBUILD_SHARED_LIBS=OFF \
      -DWITH_OPENMP=OFF -DWITH_LSR_BINDINGS=OFF -DBUILD_TESTS=OFF -DBUILD_EXAMPLES=OFF \
      -DCMAKE_TRY_COMPILE_TARGET_TYPE=STATIC_LIBRARY
    make -j"$JOBS" soxr
    make install )
fi

echo "==> shairport-sync $SP_VER"
unpack shairport-sync-$SP_VER.tar.gz shairport-sync-$SP_VER
cd "$SRC/shairport-sync-$SP_VER"
cp "$PROJ/alsa_embed.inc" .
sed -i 's|^int main(int argc, char \*\*argv) {|#include "alsa_embed.inc"\nint main(int argc, char **argv) {|' shairport.c
sed -i '/^  memset(&config, 0, sizeof(config)); \/\/ also clears/a\  embedded_alsa_conf();' shairport.c
grep -q embedded_alsa_conf shairport.c
bash "$PROJ/patch_alsa.sh" audio_alsa.c

autoreconf -fi
./configure --host=$CROSS --with-os=linux \
  --without-pkg-config \
  --with-alsa --with-pipe --with-stdout --with-dummy \
  --with-ssl=openssl --with-tinysvcmdns --with-soxr \
  --sysconfdir=/data/local/tmp
make -j"$JOBS"

mkdir -p "$PROJ/dist"
cp shairport-sync "$PROJ/dist/shairport-sync.unstripped"
$STRIP -o "$PROJ/dist/shairport-sync" shairport-sync
chmod +x "$PROJ/dist/shairport-sync"

echo "==> 完成"
file "$PROJ/dist/shairport-sync"
ls -l "$PROJ/dist/shairport-sync"
