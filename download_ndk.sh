#!/bin/bash
set -e

echo "Downloading Android NDK r28..."
cd android-sdk/ndk
curl -L -O https://dl.google.com/android/repository/android-ndk-r28-linux.zip

echo "Extracting..."
unzip -q android-ndk-r28-linux.zip

echo "Cleaning up..."
rm android-ndk-r28-linux.zip

echo "NDK r28 installed at $(pwd)/android-ndk-r28"
