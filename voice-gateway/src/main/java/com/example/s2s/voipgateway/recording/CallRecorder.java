package com.example.s2s.voipgateway.recording;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Records audio from both inbound (caller) and outbound (Nova) streams
 * and uploads to S3 as a stereo WAV file when the call completes.
 *
 * Format: 16-bit PCM, 8kHz, stereo (left=caller, right=Nova)
 */
public class CallRecorder {
    private static final Logger log = LoggerFactory.getLogger(CallRecorder.class);

    private static final int SAMPLE_RATE = 8000;
    private static final int BITS_PER_SAMPLE = 16;
    private static final int CHANNELS = 2; // Stereo

    private final ByteArrayOutputStream inboundBuffer;
    private final ByteArrayOutputStream outboundBuffer;
    private final String sessionId;
    private final String callerPhoneNumber;
    private final S3Client s3Client;
    private final String bucketName;
    private final ReentrantLock lock;
    private volatile boolean finalized;

    // Track sample counts for timing synchronization
    private int inboundSampleCount = 0;
    private int outboundSampleCount = 0;
    private long startTimeMillis;

    public CallRecorder(String sessionId, String callerPhoneNumber, S3Client s3Client, String bucketName) {
        this.sessionId = sessionId;
        this.callerPhoneNumber = callerPhoneNumber;
        this.s3Client = s3Client;
        this.bucketName = bucketName;
        this.inboundBuffer = new ByteArrayOutputStream();
        this.outboundBuffer = new ByteArrayOutputStream();
        this.lock = new ReentrantLock();
        this.finalized = false;
        this.startTimeMillis = System.currentTimeMillis();

        log.info("CallRecorder initialized for session: {} caller: {}", sessionId, callerPhoneNumber);
    }

    /**
     * Record inbound audio (caller to Nova).
     * @param pcmData 16-bit PCM audio samples (mono)
     */
    public void recordInbound(byte[] pcmData) {
        if (finalized) {
            return;
        }

        lock.lock();
        try {
            inboundBuffer.write(pcmData);
            inboundSampleCount += pcmData.length / 2;
        } catch (IOException e) {
            log.error("Error recording inbound audio", e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Record outbound audio (Nova to caller).
     * Pads with silence to synchronize timing with inbound stream.
     * @param pcmData 16-bit PCM audio samples (mono)
     */
    public void recordOutbound(byte[] pcmData) {
        if (finalized) {
            return;
        }

        lock.lock();
        try {
            // Pad outbound with silence to match inbound timing
            // This ensures both streams stay synchronized
            int silenceSamples = inboundSampleCount - outboundSampleCount;
            if (silenceSamples > 0) {
                // Add silence (zeros) to outbound to catch up with inbound
                byte[] silence = new byte[silenceSamples * 2];
                outboundBuffer.write(silence);
                outboundSampleCount += silenceSamples;
            }

            // Now write the actual audio data
            outboundBuffer.write(pcmData);
            outboundSampleCount += pcmData.length / 2;
        } catch (IOException e) {
            log.error("Error recording outbound audio", e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Finalize recording and upload to S3.
     * Mixes inbound and outbound streams into stereo WAV file.
     */
    public void finishAndUpload() {
        if (finalized) {
            return;
        }

        lock.lock();
        try {
            finalized = true;

            byte[] inboundData = inboundBuffer.toByteArray();
            byte[] outboundData = outboundBuffer.toByteArray();

            int inboundSamples = inboundData.length / 2;
            int outboundSamples = outboundData.length / 2;

            log.info("Finalizing recording: {} inbound bytes ({} samples), {} outbound bytes ({} samples)",
                     inboundData.length, inboundSamples, outboundData.length, outboundSamples);

            if (inboundSamples == 0 && outboundSamples == 0) {
                log.warn("No audio recorded for session: {} - skipping upload", sessionId);
                return;
            }

            // Mix to mono WAV (conversation style - sequential playback)
            byte[] stereoWav = mixToMonoWav(inboundData, outboundData);

            log.info("Created WAV file ({} bytes), uploading to S3 bucket: {}", stereoWav.length, bucketName);
            uploadToS3(stereoWav);

            log.info("Recording uploaded successfully for session: {}", sessionId);

        } catch (Exception e) {
            log.error("Error finalizing and uploading recording for session: {}", sessionId, e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Mix mono inbound and outbound streams into a stereo WAV file.
     * Left channel = caller (inbound), Right channel = Nova (outbound).
     * This preserves conversation timing including overlaps and barge-ins.
     */
    private byte[] mixToMonoWav(byte[] inboundData, byte[] outboundData) throws IOException {
        int inboundSamples = inboundData.length / 2;
        int outboundSamples = outboundData.length / 2;

        // Use the LONGER stream as the base length
        // This ensures we capture all audio from both participants
        int maxSamples = Math.max(inboundSamples, outboundSamples);

        log.info("Mixing audio: inbound={} samples ({}s), outbound={} samples ({}s), max={} samples ({}s)",
                 inboundSamples, inboundSamples / (float)SAMPLE_RATE,
                 outboundSamples, outboundSamples / (float)SAMPLE_RATE,
                 maxSamples, maxSamples / (float)SAMPLE_RATE);

        // Read samples into arrays
        short[] inbound = new short[maxSamples];
        short[] outbound = new short[maxSamples];

        ByteBuffer inBuf = ByteBuffer.wrap(inboundData).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer outBuf = ByteBuffer.wrap(outboundData).order(ByteOrder.LITTLE_ENDIAN);

        // Copy actual samples (rest stays as 0/silence)
        for (int i = 0; i < inboundSamples; i++) {
            inbound[i] = inBuf.getShort();
        }
        for (int i = 0; i < outboundSamples; i++) {
            outbound[i] = outBuf.getShort();
        }

        // Normalize audio levels - boost caller significantly, reduce Nova
        normalizeAudio(inbound, 8.0);  // Boost caller audio 8x
        normalizeAudio(outbound, 0.5); // Reduce Nova audio by half

        // Build stereo WAV file (left=caller, right=Nova)
        int dataChunkSize = maxSamples * 2 * 2; // stereo: samples * 2_channels * bytes_per_sample
        int fileSize = 36 + dataChunkSize;

        ByteArrayOutputStream wavStream = new ByteArrayOutputStream();
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);

        // RIFF header
        header.put("RIFF".getBytes());
        header.putInt(fileSize);
        header.put("WAVE".getBytes());

        // fmt chunk
        header.put("fmt ".getBytes());
        header.putInt(16);
        header.putShort((short) 1); // PCM
        header.putShort((short) 2); // Stereo (2 channels)
        header.putInt(SAMPLE_RATE);
        header.putInt(SAMPLE_RATE * 2 * (BITS_PER_SAMPLE / 8)); // byte rate for stereo
        header.putShort((short) (2 * (BITS_PER_SAMPLE / 8))); // block align for stereo
        header.putShort((short) BITS_PER_SAMPLE);

        // data chunk
        header.put("data".getBytes());
        header.putInt(dataChunkSize);

        wavStream.write(header.array());

        // Write stereo samples: interleave left (inbound) and right (outbound)
        ByteBuffer stereoData = ByteBuffer.allocate(maxSamples * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < maxSamples; i++) {
            stereoData.putShort(inbound[i]);  // Left channel (caller)
            stereoData.putShort(outbound[i]); // Right channel (Nova)
        }

        wavStream.write(stereoData.array());

        return wavStream.toByteArray();
    }

    /**
     * Normalize audio levels by applying gain and preventing clipping.
     * @param samples Audio samples to normalize
     * @param gain Gain multiplier (1.0 = no change, >1.0 = louder, <1.0 = quieter)
     */
    private void normalizeAudio(short[] samples, double gain) {
        for (int i = 0; i < samples.length; i++) {
            double normalized = samples[i] * gain;
            // Prevent clipping
            if (normalized > Short.MAX_VALUE) {
                normalized = Short.MAX_VALUE;
            } else if (normalized < Short.MIN_VALUE) {
                normalized = Short.MIN_VALUE;
            }
            samples[i] = (short) normalized;
        }
    }

    /**
     * Upload WAV file to S3 with metadata.
     */
    private void uploadToS3(byte[] wavData) {
        if (bucketName == null || bucketName.isEmpty()) {
            log.warn("S3 bucket not configured, skipping upload for session: {}", sessionId);
            return;
        }

        try {
            LocalDateTime now = LocalDateTime.now();
            String datePrefix = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
            String timestamp = now.format(DateTimeFormatter.ofPattern("HHmmss"));

            // Sanitize phone number for filename (remove + and other special chars)
            String sanitizedPhone = callerPhoneNumber.replaceAll("[^0-9]", "");

            // Format: YYYY-MM-DD/phone-sessionId-HHmmss.wav
            String fileName = String.format("%s/%s-%s-%s.wav", datePrefix, sanitizedPhone, sessionId, timestamp);

            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucketName)
                    .key(fileName)
                    .contentType("audio/wav")
                    .metadata(java.util.Map.of(
                            "sessionId", sessionId,
                            "callerPhoneNumber", callerPhoneNumber,
                            "timestamp", now.toString(),
                            "sampleRate", String.valueOf(SAMPLE_RATE),
                            "channels", String.valueOf(CHANNELS),
                            "bitsPerSample", String.valueOf(BITS_PER_SAMPLE)
                    ))
                    .build();

            s3Client.putObject(request, RequestBody.fromBytes(wavData));

            log.info("Uploaded recording to s3://{}/{} ({} bytes)", bucketName, fileName, wavData.length);

        } catch (Exception e) {
            log.error("Failed to upload recording to S3 for session: {}", sessionId, e);
        }
    }

    /**
     * Get recording statistics.
     */
    public RecordingStats getStats() {
        lock.lock();
        try {
            int inboundSamples = inboundBuffer.size() / 2;
            int outboundSamples = outboundBuffer.size() / 2;
            double inboundDuration = inboundSamples / (double) SAMPLE_RATE;
            double outboundDuration = outboundSamples / (double) SAMPLE_RATE;

            return new RecordingStats(
                    inboundSamples,
                    outboundSamples,
                    inboundDuration,
                    outboundDuration,
                    finalized
            );
        } finally {
            lock.unlock();
        }
    }

    public static class RecordingStats {
        public final int inboundSamples;
        public final int outboundSamples;
        public final double inboundDurationSeconds;
        public final double outboundDurationSeconds;
        public final boolean finalized;

        public RecordingStats(int inboundSamples, int outboundSamples,
                            double inboundDuration, double outboundDuration,
                            boolean finalized) {
            this.inboundSamples = inboundSamples;
            this.outboundSamples = outboundSamples;
            this.inboundDurationSeconds = inboundDuration;
            this.outboundDurationSeconds = outboundDuration;
            this.finalized = finalized;
        }

        @Override
        public String toString() {
            return String.format("RecordingStats[inbound=%.1fs (%d samples), outbound=%.1fs (%d samples), finalized=%s]",
                    inboundDurationSeconds, inboundSamples, outboundDurationSeconds, outboundSamples, finalized);
        }
    }
}
