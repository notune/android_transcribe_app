#!/bin/bash
set -e

# --- Configuration ---

# Detect SDK
if [ -d "$(pwd)/android-sdk" ]; then
    export ANDROID_HOME="$(pwd)/android-sdk"
elif [ -z "$ANDROID_HOME" ]; then
    echo "Warning: ANDROID_HOME not set. Trying standard location..."
    export ANDROID_HOME="$HOME/Android/Sdk"
fi

SDK="$ANDROID_HOME"

if [ -d "$SDK/ndk/android-ndk-r28" ]; then
    NDK="$SDK/ndk/android-ndk-r28"
    echo "Using NDK r28: $NDK"
else
    NDK="${ANDROID_NDK_HOME:-$SDK/ndk/26.1.10909125}"
    echo "Warning: NDK r28 not explicitly found, using: $NDK"
fi

if [ ! -d "$SDK" ]; then
    echo "Error: Android SDK not found at $SDK"
    exit 1
fi

# Tools
AAPT2="$SDK/build-tools/35.0.0/aapt2"
D8="$SDK/build-tools/35.0.0/d8"
PLATFORM="$SDK/platforms/android-35/android.jar"
BUNDLETOOL="libs/bundletool.jar"

# Keystore (Release - checking/generating)
KEYSTORE="release.keystore"
KEY_ALIAS="release"
KEY_PASS="${KEY_PASS:-password}"
STORE_PASS="${STORE_PASS:-password}"

if [ ! -f "$KEYSTORE" ]; then
    echo "Generating Release Keystore ($KEYSTORE)..."
    keytool -genkey -v -keystore "$KEYSTORE" \
        -alias "$KEY_ALIAS" \
        -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass "$STORE_PASS" -keypass "$KEY_PASS" \
        -dname "CN=Transcribe App,O=OpenSource,C=US"
fi

# --- Setup ---

rm -rf build_aab
mkdir -p build_aab/base/manifest build_aab/base/dex build_aab/base/res build_aab/base/root build_aab/base/assets build_aab/base/lib/arm64-v8a
mkdir -p build_aab/model_assets/manifest build_aab/model_assets/assets
mkdir -p libs

# Download Bundletool
if [ ! -f "$BUNDLETOOL" ]; then
    echo "Downloading Bundletool..."
    curl -L -o "$BUNDLETOOL" https://github.com/google/bundletool/releases/download/1.15.6/bundletool-all-1.15.6.jar
fi

# Download ONNX Runtime if missing or update needed
if [ -d "libs/onnxruntime" ]; then
    rm -rf libs/onnxruntime
fi

if [ ! -d "libs/onnxruntime" ]; then
    echo "Downloading ONNX Runtime 1.22.0..."
    curl -L -o libs/onnxruntime.aar https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/1.22.0/onnxruntime-android-1.22.0.aar
    unzip -q -o libs/onnxruntime.aar -d libs/onnxruntime
fi

# Generate Cargo Config
# Always regenerate to ensure correct flags for 16KB page size
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

# --- 1. Build Native (Rust) ---
echo "--- Building Rust (Release) ---"
export ANDROID_NDK_HOME="$NDK"
cargo build --target aarch64-linux-android --release

# --- 2. Compile Java ---
echo "--- Compiling Java ---"
mkdir -p build_aab/gen

$AAPT2 compile --dir res -o build_aab/resources.zip
$AAPT2 link -I "$PLATFORM" \
    --manifest AndroidManifest.xml \
    -o build_aab/base_res.apk \
    build_aab/resources.zip \
    --java build_aab/gen \
    --auto-add-overlay

# Compile Java
javac -d build_aab/obj \
    --release 8 \
    -classpath "$PLATFORM" \
    $(find src/java -name "*.java") $(find build_aab/gen -name "*.java")

# --- 3. Dex ---
echo "--- Dexing ---"
$D8 --output build_aab/base/dex \
    --lib "$PLATFORM" \
    $(find build_aab/obj -name "*.class")

# Move classes.dex to correct location for AAB
# Standard AAB module structure:
# - manifest/AndroidManifest.xml (proto)
# - dex/classes.dex
# - res/
# - assets/
# - lib/
# - root/ (unknown files)
# - resources.pb

# D8 outputs classes.dex in build_aab/base/dex directly.

# --- 4. Base Module ---
echo "--- Creating Base Module ---"

# Link Base Resources (Proto Format)
# Exclude assets here (we pass no -A)
$AAPT2 link --proto-format -o build_aab/base_linked.apk \
    -I "$PLATFORM" \
    --manifest AndroidManifest.xml \
    build_aab/resources.zip \
    --auto-add-overlay

# Extract base_linked.apk components to build_aab/base
unzip -q -o build_aab/base_linked.apk -d build_aab/base_extracted
cp build_aab/base_extracted/AndroidManifest.xml build_aab/base/manifest/
cp build_aab/base_extracted/resources.pb build_aab/base/
if [ -d "build_aab/base_extracted/res" ]; then
    cp -r build_aab/base_extracted/res build_aab/base/
fi

# Libraries
cp target/aarch64-linux-android/release/libandroid_transcribe_app.so build_aab/base/lib/arm64-v8a/
cp libs/onnxruntime/jni/arm64-v8a/libonnxruntime.so build_aab/base/lib/arm64-v8a/

# libc++_shared.so
LIBCPP="$NDK/toolchains/llvm/prebuilt/linux-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so"
cp "$LIBCPP" build_aab/base/lib/arm64-v8a/

# Create Base Zip
cd build_aab/base
zip -r ../base.zip .
cd ../..

# --- 5. Asset Module (parakeet_assets) ---
echo "--- Creating Asset Module ---"

# Manifest
cat > build_aab/model_assets_manifest.xml <<EOF
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:dist="http://schemas.android.com/apk/distribution"
    package="dev.notune.transcribe"
    split="model_assets">
    <dist:module dist:type="asset-pack">
        <dist:fusing dist:include="true" />
        <dist:delivery>
            <dist:install-time />
        </dist:delivery>
    </dist:module>
</manifest>
EOF

# We need to compile/link this manifest to proto format
$AAPT2 link --proto-format -o build_aab/model_assets_linked.apk \
    -I "$PLATFORM" \
    --manifest build_aab/model_assets_manifest.xml \
    -A assets

# Extract components
unzip -q -o build_aab/model_assets_linked.apk -d build_aab/model_assets_extracted
cp build_aab/model_assets_extracted/AndroidManifest.xml build_aab/model_assets/manifest/
# assets/ folder is extracted as assets/...
cp -r build_aab/model_assets_extracted/assets build_aab/model_assets/

# Asset packs must NOT have resources.pb
if [ -f "build_aab/model_assets/resources.pb" ]; then
    rm build_aab/model_assets/resources.pb
fi

# Create Asset Zip
cd build_aab/model_assets
zip -r ../model_assets.zip .
cd ../..

# --- 6. Bundle ---
echo "--- Bundling AAB ---"

# Create Bundle Config to keep native libs uncompressed
cat > build_aab/bundle_config.json <<EOF
{
  "compression": {
    "uncompressed_glob": [
      "lib/**/*.so"
    ]
  }
}
EOF

java -jar "$BUNDLETOOL" build-bundle \
    --modules=build_aab/base.zip,build_aab/model_assets.zip \
    --output=android_transcribe_app.aab \
    --config=build_aab/bundle_config.json \
    --overwrite

# --- 7. Sign ---
echo "--- Signing AAB ---"
jarsigner -keystore "$KEYSTORE" \
    -storepass "$STORE_PASS" \
    -keypass "$KEY_PASS" \
    android_transcribe_app.aab \
    "$KEY_ALIAS"

echo "SUCCESS: android_transcribe_app.aab created (Signed with release key)"
