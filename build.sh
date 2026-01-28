#!/bin/bash
set -e

# --- Configuration ---

# SDK and NDK Setup
if [ -z "$ANDROID_HOME" ]; then
    echo "Warning: ANDROID_HOME not set. Trying standard location..."
    export ANDROID_HOME="$HOME/Android/Sdk"
fi

if [ ! -d "$ANDROID_HOME" ]; then
    echo "Error: Android SDK not found at $ANDROID_HOME. Please set ANDROID_HOME."
    exit 1
fi

SDK="$ANDROID_HOME"

if [ -z "$ANDROID_NDK_HOME" ]; then
    # We require NDK r28+ for 16KB page support (aligned libc++_shared.so)
    if [ -d "$SDK/ndk/android-ndk-r28" ]; then
        export ANDROID_NDK_HOME="$SDK/ndk/android-ndk-r28"
        echo "Using NDK r28 (Required for 16KB page support): $ANDROID_NDK_HOME"
    else
        # Fallback (Might fail 16KB check)
        DETECTED_NDK=$(ls -d "$SDK/ndk/"* 2>/dev/null | sort -V | tail -n 1)
        if [ -n "$DETECTED_NDK" ]; then
            export ANDROID_NDK_HOME="$DETECTED_NDK"
            echo "Warning: NDK r28 not found. Using $ANDROID_NDK_HOME. 16KB support might be incomplete."
        else
            echo "Error: ANDROID_NDK_HOME not set and NDK not found in $SDK/ndk/."
            exit 1
        fi
    fi
fi

NDK="$ANDROID_NDK_HOME"

# Build Tools and Platform
# Prefer specific versions but fallback to latest available
TARGET_BUILD_TOOLS="35.0.0"
if [ -d "$SDK/build-tools/$TARGET_BUILD_TOOLS" ]; then
    BUILD_TOOLS="$SDK/build-tools/$TARGET_BUILD_TOOLS"
else
    BUILD_TOOLS=$(ls -d "$SDK/build-tools/"* | sort -V | tail -n 1)
    echo "Using Build Tools: $BUILD_TOOLS"
fi

TARGET_PLATFORM="android-35"
if [ -f "$SDK/platforms/$TARGET_PLATFORM/android.jar" ]; then
    PLATFORM="$SDK/platforms/$TARGET_PLATFORM/android.jar"
else
    PLATFORM_DIR=$(ls -d "$SDK/platforms/android-"* | sort -V | tail -n 1)
    PLATFORM="$PLATFORM_DIR/android.jar"
    echo "Using Platform: $PLATFORM"
fi

AAPT2="$BUILD_TOOLS/aapt2"
D8="$BUILD_TOOLS/d8"
APKSIGNER="$BUILD_TOOLS/apksigner"
ZIPALIGN="$BUILD_TOOLS/zipalign"

# Keystore
KEYSTORE="${KEYSTORE_FILE:-release.keystore}"
KEY_ALIAS="${KEY_ALIAS:-release}"
KEY_PASS="${KEY_PASS:-password}"
STORE_PASS="${STORE_PASS:-password}"

if [ -f "$KEYSTORE" ]; then
    echo "Using Keystore: $KEYSTORE"
else
    # Fallback to debug keystore if release keystore not provided/found
    KEYSTORE="${ANDROID_KEYSTORE:-$HOME/.android/debug.keystore}"
    STORE_PASS="android"
    KEY_ALIAS="androiddebugkey"
    KEY_PASS="android"
    echo "Using Debug Keystore: $KEYSTORE"
fi

# Ensure debug keystore exists if using default
if [ "$KEYSTORE" = "$HOME/.android/debug.keystore" ] && [ ! -f "$KEYSTORE" ]; then
    echo "Creating debug keystore at $KEYSTORE..."
    mkdir -p "$(dirname "$KEYSTORE")"
    keytool -genkey -v -keystore "$KEYSTORE" -storepass android -alias androiddebugkey -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
fi

# --- Build Steps ---

# Clean
rm -rf build_manual
mkdir -p build_manual/gen build_manual/obj build_manual/apk build_manual/lib/arm64-v8a

# 0. Setup Dependencies
echo "--- Checking Dependencies ---"

# Download ONNX Runtime if missing or incorrect version
if [ -d "libs/onnxruntime" ]; then
    # Clean up old version to ensure we get the new one
    rm -rf libs/onnxruntime
fi

if [ ! -d "libs/onnxruntime" ]; then
    echo "Downloading ONNX Runtime 1.22.0..."
    mkdir -p libs
    curl -L -o libs/onnxruntime.aar https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.22.0/onnxruntime-android-1.22.0.aar
    unzip -q -o libs/onnxruntime.aar -d libs/onnxruntime
    echo "ONNX Runtime extracted."
fi

# Generate Cargo Config
# Always regenerate to ensure correct flags
echo "Generating .cargo/config.toml..."
mkdir -p .cargo

# Get absolute path to NDK and Project
NDK_ABS=$(cd "$NDK" && pwd)
PROJ_ABS=$(pwd)
ORT_ABS="$PROJ_ABS/libs/onnxruntime"

# Verify NDK structure for clang
CLANG_BIN="$NDK_ABS/toolchains/llvm/prebuilt/linux-x86_64/bin"
if [ ! -d "$CLANG_BIN" ]; then
    echo "Error: Could not find NDK toolchain binaries at $CLANG_BIN"
    exit 1
fi

cat > .cargo/config.toml <<EOF
[target.aarch64-linux-android]
linker = "$CLANG_BIN/aarch64-linux-android28-clang"
rustflags = ["-C", "link-arg=-Wl,-z,max-page-size=16384", "-C", "link-arg=-lc++_shared"]

[env]
CC_aarch64_linux_android = "$CLANG_BIN/aarch64-linux-android28-clang"
CXX_aarch64_linux_android = "$CLANG_BIN/aarch64-linux-android28-clang++"
AR_aarch64_linux_android = "$CLANG_BIN/llvm-ar"
ORT_LIB_LOCATION = "$ORT_ABS/jni/arm64-v8a"
ORT_INCLUDE_DIR = "$ORT_ABS/headers"
ANDROID_NDK_HOME = "$NDK_ABS"
ANDROID_NDK = "$NDK_ABS"
BINDGEN_EXTRA_CLANG_ARGS_aarch64_linux_android = "--sysroot=$NDK_ABS/toolchains/llvm/prebuilt/linux-x86_64/sysroot"
EOF
echo ".cargo/config.toml generated."

# 1. Build Rust
echo "--- Building Rust ---"
# export ANDROID_NDK_ROOT is deprecated but some tools might still use it, standard is ANDROID_NDK_HOME
export ANDROID_NDK_ROOT="$NDK"
cargo build --target aarch64-linux-android --release

# 2. Compile Resources
echo "--- Compiling Resources ---"
$AAPT2 compile --dir res -o build_manual/resources.zip
$AAPT2 link -I "$PLATFORM" \
    --manifest AndroidManifest.xml \
    -o build_manual/apk/unaligned.apk \
    build_manual/resources.zip \
    --java build_manual/gen \
    --auto-add-overlay

# 3. Compile Java
echo "--- Compiling Java ---"
find src/java -name "*.java" > build_manual/sources.txt
find build_manual/gen -name "*.java" >> build_manual/sources.txt

javac -d build_manual/obj \
    -source 1.8 -target 1.8 \
    -classpath "$PLATFORM" \
    @build_manual/sources.txt

# 4. Dex
echo "--- Dexing ---"
find build_manual/obj -name "*.class" > build_manual/classes.txt
$D8 --output build_manual/apk \
    --lib "$PLATFORM" \
    @build_manual/classes.txt

# 5. Package
echo "--- Packaging ---"
# Copy Shared Libraries
cp target/aarch64-linux-android/release/libandroid_transcribe_app.so build_manual/lib/arm64-v8a/

# Check for ONNX Runtime
if [ -f "jniLibs/arm64-v8a/libonnxruntime.so" ]; then
    cp jniLibs/arm64-v8a/libonnxruntime.so build_manual/lib/arm64-v8a/
else
    echo "Warning: libonnxruntime.so not found in jniLibs/arm64-v8a/. Application may crash if it depends on it."
fi

# Copy libc++_shared.so from NDK
LIBCPP="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"
if [ ! -f "$LIBCPP" ]; then
    # Fallback search
    LIBCPP=$(find "$NDK" -name "libc++_shared.so" | grep "aarch64" | head -n 1)
fi

if [ -f "$LIBCPP" ]; then
    cp "$LIBCPP" build_manual/lib/arm64-v8a/
else
    echo "Error: libc++_shared.so not found in NDK."
    exit 1
fi

# Add content to APK
cd build_manual/apk

# Add classes.dex
jar uf unaligned.apk classes.dex

# Add Libs - store uncompressed for 16KB alignment compatibility
# Using zip with -0 (store without compression) for native libs
cp -r ../lib .
zip -r0 unaligned.apk lib

# Add Assets
if [ -d "../../assets" ]; then
    cp -r ../../assets .
    jar uf unaligned.apk assets
fi

cd ../..

# 6. Sign
echo "--- Aligning and Signing ---"
# -P 16 = 16KB page alignment for native libs (required for Android 15+ / Google Play Nov 2025)
# -v = verbose, 4 = standard 4-byte alignment for other files
$ZIPALIGN -f -P 16 -v 4 build_manual/apk/unaligned.apk build_manual/apk/aligned.apk
$APKSIGNER sign --ks "$KEYSTORE" \
    --ks-pass "pass:$STORE_PASS" \
    --key-pass "pass:$KEY_PASS" \
    --ks-key-alias "$KEY_ALIAS" \
    --out android_transcribe_app_release.apk \
    build_manual/apk/aligned.apk

# 7. Verify 16KB Alignment
echo "--- Verifying 16KB Alignment ---"
echo "Checking APK alignment..."
if $ZIPALIGN -c -P 16 -v 4 android_transcribe_app_release.apk 2>&1 | tail -5; then
    echo ""
else
    echo "WARNING: APK alignment verification had issues"
fi

# Check ELF alignment of native libraries
echo ""
echo "Checking ELF segment alignment..."
LLVM_OBJDUMP="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-objdump"
if [ -f "$LLVM_OBJDUMP" ]; then
    rm -rf /tmp/apk_check && mkdir -p /tmp/apk_check
    unzip -q android_transcribe_app_release.apk -d /tmp/apk_check
    for so in /tmp/apk_check/lib/arm64-v8a/*.so; do
        if [ -f "$so" ]; then
            echo "  $(basename $so):"
            ALIGN=$($LLVM_OBJDUMP -p "$so" 2>/dev/null | grep "LOAD" | head -1 | grep -o "align 2\*\*[0-9]*")
            if echo "$ALIGN" | grep -q "2\*\*14"; then
                echo "    ✓ ELF aligned to 16KB (2**14)"
            else
                echo "    ✗ WARNING: ELF alignment is $ALIGN (expected 2**14)"
            fi
        fi
    done
    rm -rf /tmp/apk_check
fi

echo ""
echo "SUCCESS: android_transcribe_app_release.apk created"
echo "The APK should now be compatible with 16KB page size devices."