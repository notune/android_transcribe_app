package dev.notune.transcribe;

import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Loads the fastest native backend supported by this device. */
final class NativeLibraries {
    private static final String TAG = "OfflineVoiceInput";
    private static final long AT_HWCAP = 16;
    private static final long HWCAP_ASIMDHP = 1L << 10;
    private static final long HWCAP_ASIMDDP = 1L << 20;
    private static boolean loaded;

    private NativeLibraries() {}

    static synchronized void load() {
        if (loaded) {
            return;
        }

        System.loadLibrary("c++_shared");
        String backend = supportsDotprodAndFp16()
                ? "android_transcribe_app_dotprod"
                : "android_transcribe_app_armv8";
        Log.i(TAG, "Loading native backend: " + backend);
        System.loadLibrary(backend);
        loaded = true;
    }

    private static boolean supportsDotprodAndFp16() {
        try {
            byte[] auxv = Files.readAllBytes(Paths.get("/proc/self/auxv"));
            ByteBuffer entries = ByteBuffer.wrap(auxv).order(ByteOrder.nativeOrder());
            while (entries.remaining() >= 16) {
                long type = entries.getLong();
                long value = entries.getLong();
                if (type == 0) {
                    break;
                }
                if (type == AT_HWCAP) {
                    return (value & HWCAP_ASIMDHP) != 0 && (value & HWCAP_ASIMDDP) != 0;
                }
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Cannot read CPU capabilities; using Armv8-A backend", e);
        }
        return false;
    }
}
