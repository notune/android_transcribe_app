package dev.notune.transcribe;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Stores the post-processing API key encrypted with a non-exportable key in
 * the Android Keystore. The ciphertext lives in the app's private preferences;
 * backups or file copies are useless without the hardware-backed key.
 */
final class SecretStore {

    private static final String TAG = "OfflineVoiceInput";
    private static final String ANDROID_KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "offline_voice_input_post_processing_key_v1";
    private static final String PREFS = "post_processing_secrets";
    private static final String KEY_CIPHERTEXT = "api_key_ciphertext";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;

    private SecretStore() {
    }

    /**
     * Stores the key and reports whether it was written. The Keystore requires
     * the cipher to choose the GCM IV when randomized encryption is on, so the
     * IV must be read back from the cipher instead of being supplied by us.
     */
    static synchronized boolean put(Context context, String value) {
        if (value == null || value.isEmpty()) {
            clear(context);
            return true;
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            byte[] iv = cipher.getIV();
            if (iv == null || iv.length == 0 || iv.length > 255) {
                Log.e(TAG, "Keystore returned an unusable GCM IV");
                return false;
            }
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));

            byte[] packed = new byte[1 + iv.length + encrypted.length];
            packed[0] = (byte) iv.length;
            System.arraycopy(iv, 0, packed, 1, iv.length);
            System.arraycopy(encrypted, 0, packed, 1 + iv.length, encrypted.length);

            return prefs(context).edit()
                    .putString(KEY_CIPHERTEXT, Base64.encodeToString(packed, Base64.NO_WRAP))
                    .commit();
        } catch (Exception e) {
            // Never log the value itself.
            Log.e(TAG, "Could not store the post-processing key", e);
            return false;
        }
    }

    /** Returns the key, or null when none is stored or it can no longer be decrypted. */
    static synchronized String get(Context context) {
        String packedText = prefs(context).getString(KEY_CIPHERTEXT, null);
        if (packedText == null || packedText.isEmpty()) return null;
        try {
            byte[] packed = Base64.decode(packedText, Base64.NO_WRAP);
            if (packed.length < 2) {
                clear(context);
                return null;
            }
            int ivLength = packed[0] & 0xff;
            if (ivLength == 0 || packed.length <= 1 + ivLength) {
                clear(context);
                return null;
            }
            byte[] iv = new byte[ivLength];
            System.arraycopy(packed, 1, iv, 0, ivLength);
            byte[] encrypted = new byte[packed.length - 1 - ivLength];
            System.arraycopy(packed, 1 + ivLength, encrypted, 0, encrypted.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception e) {
            // A reset lock screen, a restored backup or a wiped Keystore leaves
            // an undecryptable blob. Drop it so the user can enter a new key.
            Log.w(TAG, "Stored post-processing key is unreadable; clearing it");
            clear(context);
            return null;
        }
    }

    static synchronized void clear(Context context) {
        prefs(context).edit().remove(KEY_CIPHERTEXT).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance(ANDROID_KEYSTORE);
        keyStore.load(null);
        if (keyStore.containsAlias(KEY_ALIAS)) {
            return (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        }
        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }
}
