package com.example.s2s.voipgateway.nova.io;

import com.example.s2s.voipgateway.nova.transcode.PcmToULawTranscoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * An InputStream backed by a queue for sending outbound ULAW audio.
 */
public class QueuedUlawInputStream extends InputStream {
    private static final Logger log = LoggerFactory.getLogger(QueuedUlawInputStream.class);
    private static final byte SILENCE = 127;
    private LinkedBlockingQueue<byte[]> queue = new LinkedBlockingQueue<>(50000);
    private byte[] currentChunk = null;
    private int currentIndex = -1;
    private boolean open = true;
    private OutputStream testOutput;
    private boolean debugAudioSent = System.getenv().getOrDefault("DEBUG_AUDIO_SENT", "false").equalsIgnoreCase("true");

    /**
     * Appends PCM audio data to the queue.  The data is expected to be 8000 khz sample rate, 16 bit samples, 1 channel.
     *
     * @param data The audio data.
     * @throws InterruptedException If an interrupt is thrown while appending audio data to the queue.
     */
    public void append(byte[] data) throws InterruptedException {
        data = PcmToULawTranscoder.transcodeBytes(data);
        queue.put(data);

        if (debugAudioSent) {
            // Transcoded audio will be written to a .raw file for debugging purposes.  This can be opened
            // with an audio editor like Audacity (File -> Import -> Raw Data, then use U-Law encoding,
            // 8000 khz sample rate, 1 channel).
            //
            try {
                OutputStream testOutput = new FileOutputStream("bedrock.raw", true);
                testOutput.write(data);
                testOutput.close();
            } catch (IOException e) {
                log.warn("Failed to write debugging audio output", e);
            }
        }
    }

    @Override
    public int read() throws IOException {
        if (!open) {
            throw new IOException("Stream is closed!");
        }
        if (testOutput == null && debugAudioSent) {
            testOutput = new FileOutputStream("sent.raw");
        }
        if (open && (currentChunk == null || currentIndex >= currentChunk.length)) {
            try {
                if (queue.isEmpty()) {
                    if (testOutput != null) {
                        testOutput.write(SILENCE);
                    }
                    return SILENCE;
                }
                currentChunk = queue.poll(1, TimeUnit.MICROSECONDS);
                currentIndex = 0;
                //log.debug("Advance read queue to {}", currentChunk);
            } catch (InterruptedException e) {
            }
            if (currentChunk == null) {
                if (testOutput != null) {
                    testOutput.write(SILENCE);
                }
                return SILENCE; // silence is represented by 0x7f.
            }
        }
        byte readByte = currentChunk[currentIndex];
        currentIndex++;
        if (testOutput != null) {
            testOutput.write(readByte);
        }
        // -1 indicates end of stream .. just use 0 instead
        return readByte != -1 ? readByte : SILENCE;
    }

    @Override
    public void close() throws IOException {
        this.open = false;
        if (testOutput != null) {
            testOutput.close();
            testOutput = null;
        }
    }

    @Override
    public synchronized void reset() throws IOException {
    }
}
