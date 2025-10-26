package com.example.s2s.voipgateway;

import com.example.s2s.voipgateway.nova.event.NovaSonicEvent;
import com.example.s2s.voipgateway.nova.io.NovaAudioOutputStream;
import com.example.s2s.voipgateway.nova.observer.InteractObserver;
import org.mjsip.media.RtpStreamReceiver;
import org.mjsip.media.RtpStreamReceiverListener;
import org.mjsip.media.rx.*;
import org.mjsip.rtp.RtpPayloadFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zoolu.net.UdpSocket;
import org.zoolu.sound.CodecType;
import org.zoolu.util.Encoder;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;

/**
 * mjSIP AudioReceiver implementation for Nova Sonic
 */
public class NovaSonicAudioOutput implements AudioReceiver {
    private static final Logger LOG = LoggerFactory.getLogger(AudioFileReceiver.class);
    private final InteractObserver<NovaSonicEvent> inputObserver;
    private final String promptName;

    public NovaSonicAudioOutput(InteractObserver<NovaSonicEvent> inputObserver, String promptName) {
        this.inputObserver = inputObserver;
        this.promptName = promptName;
    }

    @Override
    public AudioRxHandle createReceiver(RtpReceiverOptions options, UdpSocket socket, AudioFormat audio_format,
                                        CodecType codec, int payload_type, RtpPayloadFormat payloadFormat,
                                        int sample_rate, int channels, Encoder additional_decoder,
                                        RtpStreamReceiverListener listener) throws IOException {
        NovaAudioOutputStream outputStream = new NovaAudioOutputStream(inputObserver, promptName);
        RtpStreamReceiver receiver = new RtpStreamReceiver(options, outputStream, additional_decoder, payloadFormat, socket, listener) {
            protected void onRtpStreamReceiverTerminated(Exception error) {
                super.onRtpStreamReceiverTerminated(error);
                try {
                    outputStream.close();
                } catch (IOException ex) {
                    LOG.error("Closing audio stream failed: {}", outputStream, ex);
                }

            }
        };
        return new RtpAudioRxHandler(receiver);
    }
}

