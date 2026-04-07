package dev.notune.transcribe;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Settings screen.
 *
 * Exposed settings:
 *  - Save folder for recordings and transcripts (SAF folder picker)
 *  - Default e-mail address for direct mail sharing
 */
public class SettingsActivity extends Activity {

    public static final String PREFS_NAME          = "transkript_settings";
    public static final String KEY_SAVE_FOLDER_URI = "save_folder_uri";
    public static final String KEY_EMAIL_ADDRESS   = "default_email";

    private static final int REQ_PICK_FOLDER = 301;

    private TextView folderPathText;
    private EditText emailInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings_activity);

        folderPathText = findViewById(R.id.txt_folder_path);
        emailInput     = findViewById(R.id.edit_email);

        Button chooseFolderButton = findViewById(R.id.btn_choose_folder);
        Button resetFolderButton  = findViewById(R.id.btn_reset_folder);
        Button saveEmailButton    = findViewById(R.id.btn_save_email);
        Button closeButton        = findViewById(R.id.btn_settings_close);

        refreshFolderDisplay();
        loadEmailSetting();

        chooseFolderButton.setOnClickListener(v -> openFolderPicker());
        resetFolderButton .setOnClickListener(v -> resetFolder());
        saveEmailButton   .setOnClickListener(v -> saveEmail());
        closeButton       .setOnClickListener(v -> finish());
    }

    // ------------------------------------------------------------------
    // Folder picker
    // ------------------------------------------------------------------

    private void openFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQ_PICK_FOLDER);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_FOLDER && resultCode == RESULT_OK && data != null) {
            Uri treeUri = data.getData();
            if (treeUri == null) return;
            int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            getContentResolver().takePersistableUriPermission(treeUri, flags);
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    .edit().putString(KEY_SAVE_FOLDER_URI, treeUri.toString()).apply();
            refreshFolderDisplay();
            Toast.makeText(this, getString(R.string.settings_folder_saved), Toast.LENGTH_SHORT).show();
        }
    }

    private void resetFolder() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String uriString = prefs.getString(KEY_SAVE_FOLDER_URI, null);
        if (uriString != null) {
            try {
                Uri uri = Uri.parse(uriString);
                getContentResolver().releasePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            } catch (Exception ignored) {}
        }
        prefs.edit().remove(KEY_SAVE_FOLDER_URI).apply();
        refreshFolderDisplay();
        Toast.makeText(this, getString(R.string.settings_folder_reset), Toast.LENGTH_SHORT).show();
    }

    private void refreshFolderDisplay() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String uriString = prefs.getString(KEY_SAVE_FOLDER_URI, null);
        if (uriString != null) {
            try {
                String path = Uri.parse(uriString).getLastPathSegment();
                folderPathText.setText(path != null ? path : uriString);
            } catch (Exception e) {
                folderPathText.setText(uriString);
            }
        } else {
            folderPathText.setText(getString(R.string.settings_folder_default));
        }
    }

    // ------------------------------------------------------------------
    // E-mail setting
    // ------------------------------------------------------------------

    private void loadEmailSetting() {
        String saved = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(KEY_EMAIL_ADDRESS, "");
        emailInput.setText(saved);
    }

    private void saveEmail() {
        String email = emailInput.getText().toString().trim();
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit().putString(KEY_EMAIL_ADDRESS, email).apply();
        Toast.makeText(this,
                email.isEmpty()
                        ? getString(R.string.settings_email_cleared)
                        : getString(R.string.settings_email_saved),
                Toast.LENGTH_SHORT).show();
    }
}
