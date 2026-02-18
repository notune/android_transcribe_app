package dev.notune.transcribe;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.LinearLayout;
import android.widget.Switch;
import java.io.File;
import java.io.IOException;

public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";
    private static final int PERM_REQ_CODE = 101;

    static {
        try {
            System.loadLibrary("c++_shared");
            System.loadLibrary("onnxruntime");
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "Failed to load dependencies (c++_shared or onnxruntime)", e);
        }
        System.loadLibrary("android_transcribe_app");
    }

    private TextView statusText;
    private Button grantButton;
    private View permsCard;
    private Button startSubsButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText = findViewById(R.id.text_status);
        permsCard = findViewById(R.id.card_permissions);
        grantButton = findViewById(R.id.btn_grant_perms);
        startSubsButton = findViewById(R.id.btn_subs_start);
        Button imeSettingsButton = findViewById(R.id.btn_ime_settings);

        grantButton.setOnClickListener(v -> checkAndRequestPermissions());
        
        imeSettingsButton.setOnClickListener(v -> {
             Intent intent = new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS);
             startActivity(intent);
        });

        startSubsButton.setOnClickListener(v -> {
            Intent intent = new Intent(this, LiveSubtitleActivity.class);
            startActivity(intent);
        });

        Switch autoRecordSwitch = findViewById(R.id.switch_auto_record);
        File autoRecordFile = new File(getFilesDir(), "auto_record");
        autoRecordSwitch.setChecked(autoRecordFile.exists());
        autoRecordSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                try {
                    autoRecordFile.createNewFile();
                } catch (IOException e) {
                    Log.e(TAG, "Failed to create auto_record file", e);
                }
            } else {
                autoRecordFile.delete();
            }
        });

        Switch selectTranscriptionSwitch = findViewById(R.id.switch_select_transcription);
        File selectTranscriptionFile = new File(getFilesDir(), "select_transcription");
        selectTranscriptionSwitch.setChecked(selectTranscriptionFile.exists());
        selectTranscriptionSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                try {
                    selectTranscriptionFile.createNewFile();
                } catch (IOException e) {
                    Log.e(TAG, "Failed to create select_transcription file", e);
                }
            } else {
                selectTranscriptionFile.delete();
            }
        });

        Switch pauseAudioSwitch = findViewById(R.id.switch_pause_audio);
        File pauseAudioFile = new File(getFilesDir(), "pause_audio");
        pauseAudioSwitch.setChecked(pauseAudioFile.exists());
        pauseAudioSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                try {
                    pauseAudioFile.createNewFile();
                } catch (IOException e) {
                    Log.e(TAG, "Failed to create pause_audio file", e);
                }
            } else {
                pauseAudioFile.delete();
            }
        });

        // Initial check
        updatePermissionUI();
        
        // Start init
        initNative(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionUI();
    }

    private void updatePermissionUI() {
        boolean hasAudio = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        if (hasAudio) {
            permsCard.setVisibility(View.GONE);
        } else {
            permsCard.setVisibility(View.VISIBLE);
        }
    }

    private void checkAndRequestPermissions() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, PERM_REQ_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PERM_REQ_CODE) {
            updatePermissionUI();
        }
    }

    // Called from Rust
    public void onStatusUpdate(String status) {
        runOnUiThread(() -> {
            statusText.setText("Status: " + status);
            if ("Ready".equals(status)) {
                startSubsButton.setEnabled(true);
            }
        });
    }

    private native void initNative(MainActivity activity);
}
