package dev.notune.transcribe;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

public class LiveSubtitleActivity extends Activity {
    private static final String TAG = "LiveSubtitleActivity";
    private static final int PERMISSION_CODE = 1;
    private MediaProjectionManager mProjectionManager;
    private boolean mWaitingForOverlayPermission = false;
    private boolean mProjectionStarted = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);

        if (!Settings.canDrawOverlays(this)) {
            mWaitingForOverlayPermission = true;
            openOverlaySettings();
        } else {
            startProjection();
        }
    }

    private void openOverlaySettings() {
        Toast.makeText(this, "Please grant 'Display over other apps' permission", Toast.LENGTH_LONG).show();
        
        try {
            // Use the specific app overlay settings page
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to open overlay settings", e);
            // Fallback: open app settings
            try {
                Intent appSettings = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()));
                appSettings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(appSettings);
                Toast.makeText(this, "Enable 'Display over other apps' in app settings", Toast.LENGTH_LONG).show();
            } catch (Exception e2) {
                Log.e(TAG, "Failed to open app settings", e2);
                Toast.makeText(this, "Please enable overlay permission in Settings", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        
        if (mWaitingForOverlayPermission) {
            mWaitingForOverlayPermission = false;
            if (Settings.canDrawOverlays(this)) {
                startProjection();
            } else {
                Toast.makeText(this, "Overlay permission required for subtitles", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == PERMISSION_CODE) {
            if (resultCode != RESULT_OK) {
                Toast.makeText(this, "Screen Capture denied", Toast.LENGTH_SHORT).show();
                finish();
                return;
            }

            Intent serviceIntent = new Intent(this, LiveSubtitleService.class);
            serviceIntent.setAction(LiveSubtitleService.ACTION_START);
            serviceIntent.putExtra("code", resultCode);
            serviceIntent.putExtra("data", data);
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
            finish();
        }
    }

    private void startProjection() {
        if (mProjectionStarted) {
            return;
        }
        mProjectionStarted = true;
        startActivityForResult(mProjectionManager.createScreenCaptureIntent(), PERMISSION_CODE);
    }
}
