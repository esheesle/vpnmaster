#!/usr/bin/env bash
#
# Build strongSwan native libraries for Android.
#
# Prerequisites:
#   - Android NDK (r25c+ recommended) installed and ANDROID_NDK_HOME set
#   - Git submodule initialized: git submodule update --init --recursive
#   - Standard build tools: make, autoconf, automake, libtool, pkg-config, gettext
#
# Usage:
#   ./scripts/build-strongswan.sh [--ndk /path/to/ndk] [--abi armeabi-v7a,arm64-v8a,x86,x86_64]
#
# Output:
#   Pre-built .so files are placed in app/src/main/jniLibs/<abi>/
#
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
STRONGSWAN_DIR="$PROJECT_DIR/external/strongswan"
JNILIBS_DIR="$PROJECT_DIR/app/src/main/jniLibs"

# Default ABIs to build
ABIS="armeabi-v7a,arm64-v8a,x86,x86_64"
NDK_PATH="${ANDROID_NDK_HOME:-}"

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --ndk)
            NDK_PATH="$2"
            shift 2
            ;;
        --abi)
            ABIS="$2"
            shift 2
            ;;
        *)
            echo "Unknown option: $1"
            exit 1
            ;;
    esac
done

# Validate NDK path
if [[ -z "$NDK_PATH" ]]; then
    # Try common locations
    if [[ -d "$HOME/Library/Android/sdk/ndk" ]]; then
        NDK_PATH=$(ls -d "$HOME/Library/Android/sdk/ndk"/*/ 2>/dev/null | sort -V | tail -1)
        NDK_PATH="${NDK_PATH%/}"
    elif [[ -n "${ANDROID_HOME:-}" && -d "$ANDROID_HOME/ndk" ]]; then
        NDK_PATH=$(ls -d "$ANDROID_HOME/ndk"/*/ 2>/dev/null | sort -V | tail -1)
        NDK_PATH="${NDK_PATH%/}"
    fi

    if [[ -z "$NDK_PATH" ]]; then
        echo "ERROR: Android NDK not found."
        echo "Set ANDROID_NDK_HOME or use --ndk /path/to/ndk"
        exit 1
    fi
fi

echo "Using NDK: $NDK_PATH"
echo "Building ABIs: $ABIS"
echo "strongSwan source: $STRONGSWAN_DIR"

# Verify submodule is initialized
if [[ ! -f "$STRONGSWAN_DIR/configure.ac" ]]; then
    echo "ERROR: strongSwan submodule not initialized."
    echo "Run: git submodule update --init --recursive"
    exit 1
fi

# Check for required tools
for tool in make autoconf automake libtool pkg-config; do
    if ! command -v "$tool" &> /dev/null; then
        echo "ERROR: Required tool '$tool' not found."
        echo "On macOS: brew install autoconf automake libtool pkg-config gettext"
        echo "On Ubuntu: sudo apt-get install autoconf automake libtool pkg-config gettext"
        exit 1
    fi
done

ANDROID_DIR="$STRONGSWAN_DIR/src/frontends/android"

# Generate configure if needed
if [[ ! -f "$STRONGSWAN_DIR/configure" ]]; then
    echo "Running autogen.sh..."
    cd "$STRONGSWAN_DIR"
    ./autogen.sh
    cd "$PROJECT_DIR"
fi

# Build for each ABI
IFS=',' read -ra ABI_LIST <<< "$ABIS"
for ABI in "${ABI_LIST[@]}"; do
    echo ""
    echo "=========================================="
    echo "Building for ABI: $ABI"
    echo "=========================================="

    # Map ABI to NDK toolchain arch
    case "$ABI" in
        armeabi-v7a) ARCH=arm; TARGET=armv7a-linux-androideabi ;;
        arm64-v8a)   ARCH=aarch64; TARGET=aarch64-linux-android ;;
        x86)         ARCH=x86; TARGET=i686-linux-android ;;
        x86_64)      ARCH=x86_64; TARGET=x86_64-linux-android ;;
        *)
            echo "Unknown ABI: $ABI"
            continue
            ;;
    esac

    API=26  # minSdk
    TOOLCHAIN="$NDK_PATH/toolchains/llvm/prebuilt/darwin-x86_64"
    if [[ ! -d "$TOOLCHAIN" ]]; then
        TOOLCHAIN="$NDK_PATH/toolchains/llvm/prebuilt/linux-x86_64"
    fi

    SYSROOT="$TOOLCHAIN/sysroot"
    CC="$TOOLCHAIN/bin/${TARGET}${API}-clang"
    CXX="$TOOLCHAIN/bin/${TARGET}${API}-clang++"
    AR="$TOOLCHAIN/bin/llvm-ar"
    RANLIB="$TOOLCHAIN/bin/llvm-ranlib"
    STRIP="$TOOLCHAIN/bin/llvm-strip"

    BUILD_DIR="$PROJECT_DIR/build/strongswan/$ABI"
    mkdir -p "$BUILD_DIR"

    cd "$STRONGSWAN_DIR"

    # Configure strongSwan for Android
    ./configure \
        --host="$TARGET" \
        --prefix="$BUILD_DIR/install" \
        --disable-static \
        --enable-shared \
        --disable-defaults \
        --enable-openssl \
        --enable-ikev2 \
        --enable-nonce \
        --enable-pem \
        --enable-pkcs1 \
        --enable-pkcs8 \
        --enable-x509 \
        --enable-revocation \
        --enable-constraints \
        --enable-pki \
        --enable-socket-default \
        --enable-kernel-netlink \
        --enable-resolve \
        --enable-eap-identity \
        --enable-eap-mschapv2 \
        --enable-eap-md5 \
        --enable-eap-tls \
        --enable-updown \
        --enable-android-dns \
        --enable-android-log \
        CC="$CC" CXX="$CXX" AR="$AR" RANLIB="$RANLIB" STRIP="$STRIP" \
        CFLAGS="-O2 -fPIC --sysroot=$SYSROOT" \
        LDFLAGS="--sysroot=$SYSROOT"

    make -j"$(nproc 2>/dev/null || sysctl -n hw.ncpu)" clean || true
    make -j"$(nproc 2>/dev/null || sysctl -n hw.ncpu)"
    make install DESTDIR="$BUILD_DIR/install"

    # Also build the Android JNI bridge
    cd "$ANDROID_DIR/app/src/main/jni"
    "$NDK_PATH/ndk-build" \
        NDK_PROJECT_PATH="$ANDROID_DIR/app/src/main" \
        APP_ABI="$ABI" \
        APP_PLATFORM="android-$API" \
        STRONGSWAN_DIR="$STRONGSWAN_DIR" \
        -j"$(nproc 2>/dev/null || sysctl -n hw.ncpu)" \
        || echo "WARNING: ndk-build for JNI bridge failed for $ABI. Manual build may be needed."

    # Copy .so files to jniLibs
    mkdir -p "$JNILIBS_DIR/$ABI"

    # Copy built libraries
    for lib in "$BUILD_DIR/install/usr/local/lib"/*.so; do
        if [[ -f "$lib" ]]; then
            cp "$lib" "$JNILIBS_DIR/$ABI/"
            "$STRIP" "$JNILIBS_DIR/$ABI/$(basename "$lib")" 2>/dev/null || true
        fi
    done

    # Copy JNI bridge library if built
    JNI_OUT="$ANDROID_DIR/app/src/main/libs/$ABI"
    if [[ -d "$JNI_OUT" ]]; then
        cp "$JNI_OUT"/*.so "$JNILIBS_DIR/$ABI/" 2>/dev/null || true
    fi

    echo "Completed: $ABI"
    cd "$PROJECT_DIR"
done

echo ""
echo "=========================================="
echo "strongSwan native build complete."
echo "Libraries installed to: $JNILIBS_DIR"
echo "=========================================="
ls -la "$JNILIBS_DIR"/*/
