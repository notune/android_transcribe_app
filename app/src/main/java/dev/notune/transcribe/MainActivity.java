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
import android.widget.Switch;
import java.io.File;
import java.io.IOException;

public class MainActivity extends Activity {
    private static final String TAG = "MainActivity";
    private static final int PERM_REQ_CODE    = 101;
    private static final int PERM_REQ_STORAGE = 102;
    private static final int REQ_PICK_AUDIO   = 201;

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
    private Button   grantButton;
    private View     permsCard;
    private Button   startSubsButton;
    private Button   transcribeFileButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText           = findViewById(R.id.text_status);
        permsCard            = findViewById(R.id.card_permissions);
        grantButton          = findViewById(R.id.btn_grant_perms);
        startSubsButton      = findViewById(R.id.btn_subs_start);
        transcribeFileButton = findViewById(R.id.btn_transcribe_file);
        Button imeSettingsButton = findViewById(R.id.btn_ime_settings);
        Button settingsButton    = findViewById(R.id.btn_settings);

        grantButton.setOnClickListener(v -> checkAndRequestPermissions());
        imeSettingsButton.setOnClickListener(v -> {
            startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
        });
        startSubsButton.setOnClickListener(v ->
                startActivity(new Intent(this, LiveSubtitleActivity.class)));
        transcribeFileButton.setOnClickListener(v -> pickAudioFile());
        settingsButton.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        Switch autoRecordSwitch = findViewById(R.id.switch_auto_record);
        File autoRecordFile = new File(getFilesDir(), "auto_record");
        autoRecordSwitch.setChecked(autoRecordFile.exists());
        autoRecordSwitch.setOnCheckedChangeListener((b, checked) -> {
            if (checked) { try { autoRecordFile.createNewFile(); } catch (IOException e) { Log.e(TAG, "", e); } }
            else autoRecordFile.delete();
        });

        Switch selectTranscriptionSwitch = findViewById(R.id.switch_select_transcription);
        File selectTranscriptionFile = new File(getFilesDir(), "select_transcription");
        selectTranscriptionSwitch.setChecked(selectTranscriptionFile.exists());
        selectTranscriptionSwitch.setOnCheckedChangeListener((b, checked) -> {
            if (checked) { try { selectTranscriptionFile.createNewFile(); } catch (IOException e) { Log.e(TAG, "", e); } }
            else selectTranscriptionFile.delete();
        });

        Switch pauseAudioSwitch = findViewById(R.id.switch_pause_audio);
        File pauseAudioFile = new File(getFilesDir(), "pause_audio");
        pauseAudioSwitch.setChecked(pauseAudioFile.exists());
        pauseAudioSwitch.setOnCheckedChangeListener((b, checked) -> {
            if (checked) { try { pauseAudioFile.createNewFile(); } catch (IOException e) { Log.e(TAG, "", e); } }
            else pauseAudioFile.delete();
        });

        updatePermissionUI();
        initNative(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionUI();
    }

    private void updatePermissionUI() {
        boolean hasAudio = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        permsCard.setVisibility(hasAudio ? View.GONE : View.VISIBLE);
    }

    private void checkAndRequestPermissions() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{android.Manifest.permission.RECORD_AUDIO}, PERM_REQ_CODE);
        }
    }

    private void pickAudioFile() {
        String storagePermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? android.Manifest.permission.READ_MEDIA_AUDIO
                : android.Manifest.permission.READ_EXTERNAL_STORAGE;
        if (checkSelfPermission(storagePermission) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{storagePermission}, PERM_REQ_STORAGE);
            return;
        }
        launchFilePicker();
    }

    private void launchFilePicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("audio/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(
                Intent.createChooser(intent, "Choose audio file"), REQ_PICK_AUDIO);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == PERM_REQ_CODE) {
            updatePermissionUI();
        } else if (requestCode == PERM_REQ_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                launchFilePicker();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_AUDIO && resultCode == RESULT_OK && data != null) {
            Uri audioUri = data.getData();
            if (audioUri != null) {
                Intent intent = new Intent(this, TranscribeFileActivity.class);
                intent.setAction(Intent.ACTION_VIEW);
                intent.setData(audioUri);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(intent);
            }
        }
    }

    // Called from Rust
    public void onStatusUpdate(String status) {
        runOnUiThread(() -> {
            statusText.setText("Status: " + status);
            if ("Ready".equals(status)) startSubsButton.setEnabled(true);
        });
    }

    private native void initNative(MainActivity activity);
}
