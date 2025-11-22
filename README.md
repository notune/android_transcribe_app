# Offline Voice Input (Android)

An offline, privacy-focused voice input keyboard for Android, built with Rust.

## Features

- **Offline Transcription:** Uses deep learning models (Parakeet TDT) to transcribe speech entirely on-device.
- **Privacy-First:** No audio data leaves your device.
- **Rust Backend:** Efficient and safe native code using [transcribe-rs](https://github.com/cjpais/transcribe-rs) (included).
- **Modern UI:** Built with `egui` and `eframe`.

## Prerequisites (Linux)

To build this app, you need the following system packages:

```bash
sudo pacman -Syu jdk-openjdk rustup unzip zip base-devel cmake
```
*(Adjust for your distribution: e.g., `apt install openjdk-17-jdk build-essential cmake unzip` on Ubuntu)*

Ensure you have the `aarch64-linux-android` target for Rust:
```bash
rustup target add aarch64-linux-android
```

### Android SDK Setup (Manual)

Follow these steps to set up the SDK and NDK:

1.  **Setup Directory:**
    ```bash
    mkdir -p android-sdk/cmdline-tools
    cd android-sdk
    ```

2.  **Download Tools:**
    Download the command-line tools from [Android Developers](https://developer.android.com/studio#command-tools) or use `wget`:
    ```bash
    wget https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip -O cmdline-tools.zip
    unzip -q cmdline-tools.zip
    # Move to correct structure: cmdline-tools/latest/bin
    mkdir -p cmdline-tools/latest
    mv cmdline-tools/bin cmdline-tools/lib cmdline-tools/NOTICE.txt cmdline-tools/source.properties cmdline-tools/latest/
    rm cmdline-tools.zip
    ```

3.  **Install Packages:**
    ```bash
    export ANDROID_HOME=$(pwd)
    export PATH=$PATH:$ANDROID_HOME/cmdline-tools/latest/bin
    
    yes | sdkmanager --licenses
    # Install Platform, Build Tools, and NDK (Version 26 is required)
    sdkmanager "platforms;android-33" "build-tools;33.0.2" "platform-tools" "ndk;26.1.10909125"
    ```

## Building

### Debug APK (Manual)
For quick testing, you can build a standard APK:
```bash
./build.sh
# Output: android_transcribe_app_manual.apk
```

### Release AAB (Google Play Ready)
To upload to the Play Store, you must build an Android App Bundle (.aab). This project uses **Play Asset Delivery** (Install-Time) to handle the large model files (>150MB).

1.  **Run the AAB Build Script:**
    ```bash
    ./build_aab.sh
    ```
    This will:
    - Build the Rust library in `release` mode.
    - Create a `base` module for the app code.
    - Create a `model_assets` module for the large model files (Install-Time Asset Pack).
    - Generate `android_transcribe_app.aab`.
    - Sign it with a generated `release.keystore` (password: `password`).

    **Output:** `android_transcribe_app.aab`

2.  **Testing the AAB on a Device:**
    You cannot install an `.aab` directly. Use `bundletool` (downloaded to `libs/` by the script):

    ```bash
    # 1. Generate APKs from the bundle
    java -jar libs/bundletool.jar build-apks \
        --bundle=android_transcribe_app.aab \
        --output=android_transcribe_app.apks \
        --ks=release.keystore \
        --ks-pass=pass:password \
        --ks-key-alias=release \
        --key-pass=pass:password \
        --overwrite

    # 2. Install to connected device
    # Ensure ADB is in your PATH or provide it via --adb
    java -jar libs/bundletool.jar install-apks \
        --apks=android_transcribe_app.apks \
        --adb=android-sdk/platform-tools/adb
    ```

## Installation

## License

GPLv3