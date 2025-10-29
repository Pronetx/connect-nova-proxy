package com.example.s2s.voipgateway.nova.io;

import com.example.s2s.voipgateway.recording.CallRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * An InputStream backed by a queue for sending outbound PCM16 audio in exact 320-byte frames.
 * Accumulates incoming PCM16 chunks from Nova and emits exact 320-byte frames (20ms at 8kHz, 16-bit, mono).
 * Also records outbound audio (Nova to caller) for call recording.
 */
public class QueuedPcm16InputStream extends InputStream {
    private static final Logger log = LoggerFactory.getLogger(QueuedPcm16InputStream.class);
    private static final int FRAME_SIZE = 320; // 20ms at 8kHz, 16-bit, mono

    private final LinkedBlockingQueue<byte[]> frameQueue = new LinkedBlockingQueue<>(400); // ~8s at 50fps
    private final ByteArrayOutputStream accumulator = new ByteArrayOutputStream(4096);
    private byte[] currentFrame = null;
    private int currentIndex = 0;
    private boolean open = true;
    private CallRecorder callRecorder;
    private int framesEnqueued = 0;

    /**
     * Set the call recorder for recording outbound audio.
     * @param recorder The call recorder instance
     */
    public void setCallRecorder(CallRecorder recorder) {
        this.callRecorder = recorder;
    }

    /**
     * Appends PCM16 audio data to the accumulator. Data is expected to be 8000Hz sample rate,
     * 16-bit samples, 1 channel, little-endian. This method will automatically frame the data
     * into exact 320-byte chunks before enqueuing.
     *
     * @param data The PCM16 audio data
     * @throws InterruptedException If interrupted while enqueuing
     */
    public void append(byte[] data) throws InterruptedException {
        if (data == null || data.length == 0) {
            return;
        }

        // Record outbound audio (Nova to caller) before framing
        if (callRecorder != null) {
            callRecorder.recordOutbound(data);
        }

        synchronized (accumulator) {
            try {
                accumulator.write(data);

                // Drain in exact 320-byte frames
                byte[] accBytes = accumulator.toByteArray();
                int offset = 0;

                while (accBytes.length - offset >= FRAME_SIZE) {
                    byte[] frame = new byte[FRAME_SIZE];
                    System.arraycopy(accBytes, offset, frame, 0, FRAME_SIZE);

                    // Offer frame with backpressure handling
                    if (!frameQueue.offer(frame)) {
                        // Drop oldest frame to prevent unbounded growth
                        frameQueue.poll();
                        frameQueue.offer(frame);
                        log.debug("Dropped oldest frame due to backpressure");
                    }

                    framesEnqueued++;
                    if (framesEnqueued == 1) {
                        log.info("Nova → FS: enqueued first 320-byte PCM16 frame");
                    }

                    offset += FRAME_SIZE;
                }

                // Keep remainder for next append
                accumulator.reset();
                if (offset < accBytes.length) {
                    accumulator.write(accBytes, offset, accBytes.length - offset);
                }
            } catch (IOException e) {
                log.error("PCM accumulator error", e);
            }
        }
    }

    @Override
    public int read() throws IOException {
        if (!open) {
            throw new IOException("Stream is closed!");
        }

        // Advance to next frame if needed
        if (currentFrame == null || currentIndex >= currentFrame.length) {
            try {
                currentFrame = frameQueue.poll(10, TimeUnit.MILLISECONDS);
                currentIndex = 0;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }

            // Return silence if no frame available
            if (currentFrame == null) {
                return 0; // PCM16 silence
            }
        }

        byte readByte = currentFrame[currentIndex];
        currentIndex++;
        return readByte & 0xFF; // Return unsigned byte value
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (!open) {
            throw new IOException("Stream is closed!");
        }
        if (b == null) {
            throw new NullPointerException();
        }
        if (off < 0 || len < 0 || len > b.length - off) {
            throw new IndexOutOfBoundsException();
        }
        if (len == 0) {
            return 0;
        }

        int totalRead = 0;

        while (totalRead < len) {
            // Advance to next frame if needed
            if (currentFrame == null || currentIndex >= currentFrame.length) {
                try {
                    currentFrame = frameQueue.poll(10, TimeUnit.MILLISECONDS);
                    currentIndex = 0;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }

                // No more frames available
                if (currentFrame == null) {
                    break;
                }
            }

            // Copy from current frame
            int available = currentFrame.length - currentIndex;
            int toCopy = Math.min(available, len - totalRead);
            System.arraycopy(currentFrame, currentIndex, b, off + totalRead, toCopy);
            currentIndex += toCopy;
            totalRead += toCopy;
        }

        return totalRead > 0 ? totalRead : 0; // Return 0 for silence instead of -1
    }

    @Override
    public void close() throws IOException {
        this.open = false;
    }

    /**
     * Clears the audio queue immediately. Used for barge-in/interruption handling.
     */
    public void clearQueue() {
        int discarded = frameQueue.size();
        frameQueue.clear();
        currentFrame = null;
        currentIndex = 0;
        synchronized (accumulator) {
            accumulator.reset();
        }
        log.info("Clearing Nova→FS downlink queue due to barge-in ({} frames discarded)", discarded);
    }

    /**
     * Get the number of frames currently enqueued and ready to send.
     */
    public int getQueuedFrameCount() {
        return frameQueue.size();
    }
}
