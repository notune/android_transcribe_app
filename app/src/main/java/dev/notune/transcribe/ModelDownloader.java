package dev.notune.transcribe;

import android.app.DownloadManager;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;

/**
 * Downloads the speech model (GGUF) on first run so the APK stays small
 * (~15 MB instead of ~485 MB). The file lands in filesDir/models/, which
 * assets.rs::extract_builtin_model checks first.
 */
public final class ModelDownloader {
    public static final String MODEL_NAME = "parakeet-tdt-0.6b-v3-Q4_K_M.gguf";
    public static final String MODEL_URL =
            "https://huggingface.co/handy-computer/parakeet-tdt-0.6b-v3-gguf/resolve/main/"
                    + MODEL_NAME;
    public static final String MODEL_SHA256 =
            "b68557be1e3c40207fd7c4bd9d63f1d3316b963f15325bfb0cc16a8bb0ffd181";

    /** True if a valid model is already present in filesDir/models/. */
    public static boolean modelPresent(Context ctx) {
        File f = new File(modelDir(ctx), MODEL_NAME);
        return f.isFile() && f.length() > 1_000_000;
    }

    public static File modelDir(Context ctx) {
        return new File(ctx.getFilesDir(), "models");
    }

    /** Starts the system DownloadManager (resumable, no app code running). */
    public static long startDownload(Context ctx) {
        File dir = modelDir(ctx);
        dir.mkdirs();
        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(MODEL_URL));
        req.setTitle("Speech model (Parakeet)");
        req.setDescription("One-time download (~350 MB)");
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        req.setDestinationUri(Uri.fromFile(new File(dir, MODEL_NAME)));
        DownloadManager dm = (DownloadManager) ctx.getSystemService(Context.DOWNLOAD_SERVICE);
        return dm.enqueue(req);
    }

    /** Verify sha256 of a downloaded file. */
    public static boolean verifySha256(File f) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] buf = new byte[1 << 16];
                int n;
                while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString().equals(MODEL_SHA256);
        } catch (Exception e) {
            return false;
        }
    }

    /** Move a verified download from Downloads into filesDir/models/. */
    public static boolean importFromDownloads(Context ctx) {
        File downloads = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);
        File src = new File(downloads, MODEL_NAME);
        if (!src.isFile()) return false;
        File dir = modelDir(ctx);
        dir.mkdirs();
        File dst = new File(dir, MODEL_NAME);
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (IOException e) {
            return false;
        }
        return verifySha256(dst);
    }

    private ModelDownloader() {}
}
