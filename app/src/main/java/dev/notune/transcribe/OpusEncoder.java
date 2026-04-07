package dev.notune.transcribe;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Encodes raw 16-bit PCM (16 kHz mono) to Opus inside an OGG container
 * using Android's built-in MediaCodec pipeline.
 *
 * Supported on API 21+ (Android 5.0).
 *
 * Usage:
 *   File out = OpusEncoder.encode(context.getCacheDir(), "basename", pcmBytes);
 */
public final class OpusEncoder {

    private static final String TAG          = "OpusEncoder";
    private static final String MIME         = MediaFormat.MIMETYPE_AUDIO_OPUS;
    private static final int    SAMPLE_RATE  = 16_000;  // Whisper/Parakeet native rate
    private static final int    CHANNELS     = 1;       // mono
    private static final int    BITRATE      = 24_000;  // 24 kbps – very good for speech
    private static final int    FRAME_MICROS = 20_000;  // 20 ms per Opus frame
    private static final long   TIMEOUT_US   = 10_000;

    private OpusEncoder() {}

    /**
     * Encodes {@code pcm16} (signed 16-bit little-endian PCM at 16 kHz mono)
     * to an OGG/Opus file.
     *
     * @param dir      directory to write the file into
     * @param baseName file base name without extension
     * @param pcm16    raw PCM bytes
     * @return the written File, or null on error
     */
    public static File encode(File dir, String baseName, byte[] pcm16) {
        File outFile = new File(dir, baseName + ".ogg");
        MediaCodec codec = null;
        MediaMuxer muxer = null;

        try {
            // --- codec setup ---
            MediaFormat fmt = MediaFormat.createAudioFormat(MIME, SAMPLE_RATE, CHANNELS);
            fmt.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
            fmt.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 8192);

            codec = MediaCodec.createEncoderByType(MIME);
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();

            muxer = new MediaMuxer(outFile.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG);

            int trackIndex = -1;
            boolean muxerStarted = false;

            // Samples per frame (16-bit = 2 bytes/sample)
            int samplesPerFrame = SAMPLE_RATE * FRAME_MICROS / 1_000_000;
            int bytesPerFrame   = samplesPerFrame * 2;

            int inputOffset = 0;
            boolean inputDone   = false;
            boolean outputDone  = false;
            long presentationUs = 0;

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            while (!outputDone) {
                // --- feed input ---
                if (!inputDone) {
                    int idx = codec.dequeueInputBuffer(TIMEOUT_US);
                    if (idx >= 0) {
                        ByteBuffer buf = codec.getInputBuffer(idx);
                        if (buf == null) continue;
                        buf.clear();

                        if (inputOffset >= pcm16.length) {
                            codec.queueInputBuffer(idx, 0, 0,
                                    presentationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            int remaining = pcm16.length - inputOffset;
                            int toWrite   = Math.min(remaining, Math.min(buf.capacity(), bytesPerFrame));
                            buf.put(pcm16, inputOffset, toWrite);
                            codec.queueInputBuffer(idx, 0, toWrite, presentationUs, 0);
                            inputOffset   += toWrite;
                            presentationUs += FRAME_MICROS;
                        }
                    }
                }

                // --- drain output ---
                int outIdx = codec.dequeueOutputBuffer(info, TIMEOUT_US);
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!muxerStarted) {
                        trackIndex   = muxer.addTrack(codec.getOutputFormat());
                        muxer.start();
                        muxerStarted = true;
                    }
                } else if (outIdx >= 0) {
                    if (!muxerStarted) {
                        // format change came late – shouldn't happen but guard anyway
                        trackIndex   = muxer.addTrack(codec.getOutputFormat());
                        muxer.start();
                        muxerStarted = true;
                    }

                    ByteBuffer outBuf = codec.getOutputBuffer(outIdx);
                    if (outBuf != null && info.size > 0
                            && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        outBuf.position(info.offset);
                        outBuf.limit(info.offset + info.size);
                        muxer.writeSampleData(trackIndex, outBuf, info);
                    }

                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0)
                        outputDone = true;

                    codec.releaseOutputBuffer(outIdx, false);
                }
            }

            return outFile;

        } catch (IOException e) {
            Log.e(TAG, "Opus encoding failed", e);
            if (outFile.exists()) outFile.delete();
            return null;
        } finally {
            if (codec  != null) { try { codec.stop();  codec.release();  } catch (Exception ignored) {} }
            if (muxer  != null) { try { muxer.stop();  muxer.release();  } catch (Exception ignored) {} }
        }
    }
}
