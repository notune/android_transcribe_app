package dev.notune.transcribe;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.widget.Toast;

/**
 * Invisible activity whose only job is to ask for RECORD_AUDIO (an
 * AccessibilityService cannot show permission dialogs itself).
 * If the permission is already granted it exits instantly without
 * changing the window, so the user never notices it.
 */
public class BubblePermissionActivity extends Activity {

    private static final int REQ = 77;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            // Already granted — hand back control silently
            finish();
            return;
        }
        requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, REQ);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == REQ && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            // Resume dictation now that the mic is granted
            Intent i = new Intent(this, BubbleResumeReceiver.class);
            i.setAction(BubbleResumeReceiver.ACTION);
            sendBroadcast(i);
        } else {
            Toast.makeText(this, "Microphone permission denied — bubble cannot dictate", Toast.LENGTH_LONG).show();
        }
        finish();
    }
}
