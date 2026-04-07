package dev.notune.transcribe;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;

public class TranscribeFileActivity extends Activity {

    private static final String TAG              = "TranscribeFileActivity";
    private static final int    TARGET_SAMPLE_RATE = 16_000;

    private TextView    statusText;
    private ProgressBar progressBar;
    private View        progressArea;
    private ScrollView  resultArea;
    private TextView    resultText;
    private Button      copyButton;
    private Button      saveButton;
    private Button      shareButton;
    private Button      mailButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.transcribe_file_activity);

        statusText   = findViewById(R.id.txt_status);
        progressBar  = findViewById(R.id.progress_bar);
        progressArea = findViewById(R.id.progress_area);
        resultArea   = findViewById(R.id.result_area);
        resultText   = findViewById(R.id.txt_result);
        copyButton   = findViewById(R.id.btn_copy);
        saveButton   = findViewById(R.id.btn_save);
        shareButton  = findViewById(R.id.btn_share);
        mailButton   = findViewById(R.id.btn_mail);

        findViewById(R.id.btn_close).setOnClickListener(v -> finish());

        copyButton.setOnClickListener(v -> {
            String text = resultText.getText().toString();
            if (!text.isEmpty()) {
                ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cb.setPrimaryClip(ClipData.newPlainText("Transcription", text));
                Toast.makeText(this, getString(R.string.transcript_copied), Toast.LENGTH_SHORT).show();
            }
        });

        saveButton.setOnClickListener(v -> saveTranscript());

        shareButton.setOnClickListener(v -> {
            String text = resultText.getText().toString();
            if (text.isEmpty()) return;
            // Share as plain text via Android share sheet
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_TEXT, text);
            startActivity(Intent.createChooser(intent,
                    getString(R.string.transcript_share_title)));
        });

        mailButton.setOnClickListener(v -> sendByEmail());

        startDecodeAndTranscribe();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        cleanupNative();
    }

    // ------------------------------------------------------------------
    // E-mail
    // ------------------------------------------------------------------

    private void sendByEmail() {
        String text = resultText.getText().toString();
        if (text.isEmpty()) return;

        SharedPreferences prefs = getSharedPreferences(
                SettingsActivity.PREFS_NAME, MODE_PRIVATE);
        String defaultEmail = prefs.getString(SettingsActivity.KEY_EMAIL_ADDRESS, "");

        Intent intent = new Intent(Intent.ACTION_SENDTO);
        intent.setData(Uri.parse("mailto:"));
        if (!defaultEmail.isEmpty())
            intent.putExtra(Intent.EXTRA_EMAIL, new String[]{defaultEmail});
        intent.putExtra(Intent.EXTRA_SUBJECT,
                getString(R.string.mail_subject_prefix) + " "
                        + java.time.LocalDate.now());
        intent.putExtra(Intent.EXTRA_TEXT, text);

        if (intent.resolveActivity(getPackageManager()) != null)
            startActivity(intent);
        else
            Toast.makeText(this, getString(R.string.mail_no_app), Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------
    // Save
    // ------------------------------------------------------------------

    private void saveTranscript() {
        String text = resultText.getText().toString();
        if (text.isEmpty()) return;
        new Thread(() -> {
            String saved = TranscribeSaver.saveTranscript(this, text);
            runOnUiThread(() -> {
                if (saved != null)
                    Toast.makeText(this,
                            getString(R.string.transcript_saved, saved),
                            Toast.LENGTH_LONG).show();
                else
                    Toast.makeText(this,
                            getString(R.string.transcript_save_error),
                            Toast.LENGTH_SHORT).show();
            });
        }).start();
    }

    // ------------------------------------------------------------------
    // Transcription callbacks (called from Rust)
    // ------------------------------------------------------------------

    public void onStatusUpdate(String status) {
        runOnUiThread(() -> statusText.setText(status));
    }

    public void onTextTranscribed(String text) {
        runOnUiThread(() -> {
            progressArea.setVisibility(View.GONE);
            resultArea  .setVisibility(View.VISIBLE);
            copyButton  .setVisibility(View.VISIBLE);
            saveButton  .setVisibility(View.VISIBLE);
            shareButton .setVisibility(View.VISIBLE);
            mailButton  .setVisibility(View.VISIBLE);
            resultText  .setText(text);

            // Auto-copy
            ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cb.setPrimaryClip(ClipData.newPlainText("Transcription", text));
            Toast.makeText(this, getString(R.string.transcript_copied), Toast.LENGTH_LONG).show();
        });
    }

    // ------------------------------------------------------------------
    // Audio decoding
    // ------------------------------------------------------------------

    private Uri getAudioUri() {
        Intent intent = getIntent();
        if (intent == null) return null;
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action))
            return intent.getParcelableExtra(Intent.EXTRA_STREAM);
        return intent.getData();
    }

    private void startDecodeAndTranscribe() {
        Uri audioUri = getAudioUri();
        if (audioUri == null) {
            statusText.setText(getString(R.string.error_no_audio));
            return;
        }
        initNative(this);
        new Thread(() -> {
            try {
                float[] samples = decodeAudioToSamples(audioUri);
                if (samples == null) {
                    runOnUiThread(() -> statusText.setText("Error: Could not decode audio file"));
                    return;
                }
                runOnUiThread(() -> statusText.setText("Transkribiere…"));
                transcribeAudio(samples, samples.length);
            } catch (Exception e) {
                Log.e(TAG, "Error decoding audio", e);
                runOnUiThread(() -> statusText.setText("Error: " + e.getMessage()));
            }
        }).start();
    }

    private float[] decodeAudioToSamples(Uri uri) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        extractor.setDataSource(this, uri, null);

        int audioTrackIndex = -1;
        MediaFormat inputFormat = null;
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat fmt = extractor.getTrackFormat(i);
            if (fmt.getString(MediaFormat.KEY_MIME).startsWith("audio/")) {
                audioTrackIndex = i;
                inputFormat = fmt;
                break;
            }
        }
        if (audioTrackIndex < 0) return null;

        extractor.selectTrack(audioTrackIndex);
        int sampleRate  = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE);
        int channelCount = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT);

        MediaCodec codec = MediaCodec.createDecoderByType(
                inputFormat.getString(MediaFormat.KEY_MIME));
        codec.configure(inputFormat, null, null, 0);
        codec.start();

        List<float[]> allChunks  = new ArrayList<>();
        int totalSamples = 0;
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        boolean inputDone  = false;
        boolean outputDone = false;
        long timeoutUs = 10_000;

        while (!outputDone) {
            if (!inputDone) {
                int idx = codec.dequeueInputBuffer(timeoutUs);
                if (idx >= 0) {
                    ByteBuffer buf = codec.getInputBuffer(idx);
                    int bytesRead = extractor.readSampleData(buf, 0);
                    if (bytesRead < 0) {
                        codec.queueInputBuffer(idx, 0, 0, 0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                        inputDone = true;
                    } else {
                        codec.queueInputBuffer(idx, 0, bytesRead,
                                extractor.getSampleTime(), 0);
                        extractor.advance();
                    }
                }
            }
            int outIdx = codec.dequeueOutputBuffer(bufferInfo, timeoutUs);
            if (outIdx >= 0) {
                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0)
                    outputDone = true;
                ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
                if (outBuf != null && bufferInfo.size > 0) {
                    outBuf.position(bufferInfo.offset);
                    outBuf.limit(bufferInfo.offset + bufferInfo.size);
                    ShortBuffer sb = outBuf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
                    int monoCount = sb.remaining() / channelCount;
                    float[] chunk = new float[monoCount];
                    for (int i = 0; i < monoCount; i++) {
                        if (channelCount == 1) {
                            chunk[i] = sb.get() / 32768.0f;
                        } else {
                            float sum = 0;
                            for (int c = 0; c < channelCount; c++) sum += sb.get() / 32768.0f;
                            chunk[i] = sum / channelCount;
                        }
                    }
                    allChunks.add(chunk);
                    totalSamples += monoCount;
                }
                codec.releaseOutputBuffer(outIdx, false);
            }
        }
        codec.stop(); codec.release();
        extractor.release();

        float[] mono = mergeChunks(allChunks, totalSamples);
        if (sampleRate != TARGET_SAMPLE_RATE)
            mono = resample(mono, sampleRate, TARGET_SAMPLE_RATE);
        return mono;
    }

    private float[] mergeChunks(List<float[]> chunks, int total) {
        float[] result = new float[total];
        int offset = 0;
        for (float[] c : chunks) {
            System.arraycopy(c, 0, result, offset, c.length);
            offset += c.length;
        }
        return result;
    }

    private float[] resample(float[] input, int from, int to) {
        double ratio = (double) from / to;
        float[] output = new float[(int) (input.length / ratio)];
        for (int i = 0; i < output.length; i++) {
            double src  = i * ratio;
            int    idx  = (int) src;
            double frac = src - idx;
            output[i] = (idx + 1 < input.length)
                    ? (float) (input[idx] * (1 - frac) + input[idx + 1] * frac)
                    : (idx < input.length ? input[idx] : 0);
        }
        return output;
    }

    private native void initNative(TranscribeFileActivity activity);
    private native void cleanupNative();
    private native void transcribeAudio(float[] samples, int length);
}
