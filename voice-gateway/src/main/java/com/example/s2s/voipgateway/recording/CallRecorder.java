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

    public CallRecorder(String sessionId, String callerPhoneNumber, S3Client s3Client, String bucketName) {
        this.sessionId = sessionId;
        this.callerPhoneNumber = callerPhoneNumber;
        this.s3Client = s3Client;
        this.bucketName = bucketName;
        this.inboundBuffer = new ByteArrayOutputStream();
        this.outboundBuffer = new ByteArrayOutputStream();
        this.lock = new ReentrantLock();
        this.finalized = false;

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
        } catch (IOException e) {
            log.error("Error recording inbound audio", e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Record outbound audio (Nova to caller).
     * @param pcmData 16-bit PCM audio samples (mono)
     */
    public void recordOutbound(byte[] pcmData) {
        if (finalized) {
            return;
        }

        lock.lock();
        try {
            outboundBuffer.write(pcmData);
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
            log.warn("CallRecorder already finalized for session: {}", sessionId);
            return;
        }

        lock.lock();
        try {
            finalized = true;

            byte[] inboundData = inboundBuffer.toByteArray();
            byte[] outboundData = outboundBuffer.toByteArray();

            int inboundSamples = inboundData.length / 2; // 16-bit = 2 bytes per sample
            int outboundSamples = outboundData.length / 2;

            log.info("Finalizing recording: {} inbound samples, {} outbound samples",
                     inboundSamples, outboundSamples);

            if (inboundSamples == 0 && outboundSamples == 0) {
                log.warn("No audio recorded for session: {}", sessionId);
                return;
            }

            // Mix to stereo WAV
            byte[] stereoWav = mixToStereoWav(inboundData, outboundData);

            // Upload to S3
            uploadToS3(stereoWav);

            log.info("Recording uploaded successfully for session: {}", sessionId);

        } catch (Exception e) {
            log.error("Error finalizing and uploading recording for session: {}", sessionId, e);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Mix mono inbound and outbound streams into stereo WAV file.
     * Left channel: inbound (caller)
     * Right channel: outbound (Nova)
     */
    private byte[] mixToStereoWav(byte[] inboundData, byte[] outboundData) throws IOException {
        int inboundSamples = inboundData.length / 2;
        int outboundSamples = outboundData.length / 2;
        int maxSamples = Math.max(inboundSamples, outboundSamples);

        // Calculate WAV file size
        int dataChunkSize = maxSamples * 2 * 2; // samples * channels * bytes_per_sample
        int fileSize = 36 + dataChunkSize; // WAV header is 44 bytes, file size = header - 8 + data

        ByteArrayOutputStream wavStream = new ByteArrayOutputStream();
        ByteBuffer buffer = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);

        // RIFF header
        buffer.put("RIFF".getBytes());
        buffer.putInt(fileSize);
        buffer.put("WAVE".getBytes());

        // fmt chunk
        buffer.put("fmt ".getBytes());
        buffer.putInt(16); // fmt chunk size
        buffer.putShort((short) 1); // PCM format
        buffer.putShort((short) CHANNELS); // Stereo
        buffer.putInt(SAMPLE_RATE);
        buffer.putInt(SAMPLE_RATE * CHANNELS * (BITS_PER_SAMPLE / 8)); // byte rate
        buffer.putShort((short) (CHANNELS * (BITS_PER_SAMPLE / 8))); // block align
        buffer.putShort((short) BITS_PER_SAMPLE);

        // data chunk
        buffer.put("data".getBytes());
        buffer.putInt(dataChunkSize);

        wavStream.write(buffer.array());

        // Interleave stereo samples (L, R, L, R, ...)
        ByteBuffer inboundBuf = ByteBuffer.wrap(inboundData).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer outboundBuf = ByteBuffer.wrap(outboundData).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer stereoBuf = ByteBuffer.allocate(maxSamples * 4).order(ByteOrder.LITTLE_ENDIAN);

        for (int i = 0; i < maxSamples; i++) {
            // Left channel (inbound/caller)
            if (i < inboundSamples) {
                stereoBuf.putShort(inboundBuf.getShort());
            } else {
                stereoBuf.putShort((short) 0); // Silence
            }

            // Right channel (outbound/Nova)
            if (i < outboundSamples) {
                stereoBuf.putShort(outboundBuf.getShort());
            } else {
                stereoBuf.putShort((short) 0); // Silence
            }
        }

        wavStream.write(stereoBuf.array());

        return wavStream.toByteArray();
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
            String fileName = String.format("%s/%s-%s.wav", datePrefix, sessionId, timestamp);

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
