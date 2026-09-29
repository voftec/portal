#!/usr/bin/env bash
# Builds OpenSSL 3.0.22 (latest 3.0.x LTS patch release, published 2026-08-25)
# statically for one Android ABI using the NDK toolchain.
# Usage: build-openssl.sh <armeabi-v7a|arm64-v8a>
# Output: native/prebuilt/<abi>/{lib/libcrypto.a,include/...}
set -euo pipefail
ABI="${1:?usage: build-openssl.sh <abi>}"
cd "$(dirname "$0")/.."
ROOT="$PWD"

OPENSSL_VERSION=3.0.22
OPENSSL_SHA256=67ebca7e50d17383028045486653492195b83db95f8558709701bb47b5c1ef81
OPENSSL_URL="https://github.com/openssl/openssl/releases/download/openssl-${OPENSSL_VERSION}/openssl-${OPENSSL_VERSION}.tar.gz"

DL="$ROOT/native/dl"
DEPS="$ROOT/native/deps"
OUT="$ROOT/native/prebuilt/$ABI"
mkdir -p "$DL" "$DEPS"

TARBALL="$DL/openssl-${OPENSSL_VERSION}.tar.gz"
SRC="$DEPS/openssl-${OPENSSL_VERSION}"

if [ ! -d "$SRC" ]; then
    if [ ! -f "$TARBALL" ]; then
        curl -fSL --retry 3 -o "$TARBALL" "$OPENSSL_URL"
    fi
    actual=$(shasum -a 256 "$TARBALL" | awk '{print $1}')
    [ "$actual" = "$OPENSSL_SHA256" ] || { echo "sha256 mismatch for $TARBALL" >&2; exit 1; }
    tar -xf "$TARBALL" -C "$DEPS"
fi

ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[ -n "$ANDROID_HOME" ] || { echo "ANDROID_HOME not set" >&2; exit 1; }
NDK="$ANDROID_HOME/ndk/27.0.12077973"
[ -d "$NDK" ] || { echo "NDK not found at $NDK" >&2; exit 1; }
HOST_TAG="$(ls "$NDK/toolchains/llvm/prebuilt/" | head -1)"
export ANDROID_NDK_ROOT="$NDK"
export ANDROID_NDK_HOME="$NDK"
export PATH="$NDK/toolchains/llvm/prebuilt/$HOST_TAG/bin:$PATH"

MIN_SDK=28
case "$ABI" in
    armeabi-v7a)
        TARGET="android-arm"
        TRIPLE="armv7a-linux-androideabi$MIN_SDK"
        ;;
    arm64-v8a)
        TARGET="android-arm64"
        TRIPLE="aarch64-linux-android$MIN_SDK"
        ;;
    *) echo "unsupported ABI $ABI" >&2; exit 1 ;;
esac

export CC="$TRIPLE-clang"
export CXX="$TRIPLE-clang++"
export AR=llvm-ar
export RANLIB=llvm-ranlib

BUILD="$DEPS/build-openssl-$ABI"
rm -rf "$BUILD"
mkdir -p "$BUILD"
cd "$BUILD"

"$SRC/Configure" "$TARGET" \
    -D__ANDROID_API__=$MIN_SDK \
    no-shared no-asm no-dso no-tests \
    --prefix="$OUT"

make -j"$(sysctl -n hw.ncpu)" build_libs
make install_dev

echo ">> openssl $OPENSSL_VERSION built for $ABI -> $OUT"
