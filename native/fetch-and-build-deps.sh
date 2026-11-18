#!/usr/bin/env bash
# Downloads (verifying pinned sha256) and unpacks the native dependencies:
#   - libplist 2.7.0 sources -> native/deps/libplist-2.7.0 (compiled inside our CMake)
#   - OpenSSL 3.0.22       -> built per-ABI by build-openssl.sh -> native/prebuilt/<abi>/
# Idempotent: re-running skips work that is already done.
set -euo pipefail
cd "$(dirname "$0")/.."
ROOT="$PWD"
DL="$ROOT/native/dl"
DEPS="$ROOT/native/deps"
mkdir -p "$DL" "$DEPS"

PLIST_VERSION=2.7.0
PLIST_SHA256=7ac42301e896b1ebe3c654634780c82baa7cb70df8554e683ff89f7c2643eb8b
PLIST_URL="https://github.com/libimobiledevice/libplist/releases/download/${PLIST_VERSION}/libplist-${PLIST_VERSION}.tar.bz2"

fetch() { # url out sha256
    local url="$1" out="$2" sha="$3"
    if [ ! -f "$out" ]; then
        echo ">> downloading $url"
        curl -fSL --retry 3 -o "$out" "$url"
    fi
    local actual
    actual=$(shasum -a 256 "$out" | awk '{print $1}')
    if [ "$actual" != "$sha" ]; then
        echo "sha256 mismatch for $out: got $actual want $sha" >&2
        exit 1
    fi
}

# --- libplist sources ---
if [ ! -d "$DEPS/libplist-$PLIST_VERSION" ]; then
    fetch "$PLIST_URL" "$DL/libplist-$PLIST_VERSION.tar.bz2" "$PLIST_SHA256"
    tar -xf "$DL/libplist-$PLIST_VERSION.tar.bz2" -C "$DEPS"
fi

# --- OpenSSL (per ABI) ---
ABIS="${ABIS:-armeabi-v7a arm64-v8a}"
for abi in $ABIS; do
    if [ -f "$ROOT/native/prebuilt/$abi/lib/libcrypto.a" ]; then
        echo ">> openssl already built for $abi"
        continue
    fi
    bash "$ROOT/native/build-openssl.sh" "$abi"
done

echo ">> deps ready"
