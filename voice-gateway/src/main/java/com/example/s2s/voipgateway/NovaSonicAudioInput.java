package com.example.s2s.voipgateway;

import com.example.s2s.voipgateway.nova.NovaS2SEventHandler;
import org.mjsip.media.RtpStreamSender;
import org.mjsip.media.RtpStreamSenderListener;
import org.mjsip.media.tx.AudioTXHandle;
import org.mjsip.media.tx.AudioTransmitter;
import org.mjsip.media.tx.RtpAudioTxHandle;
import org.mjsip.media.tx.RtpSenderOptions;
import org.mjsip.rtp.RtpControl;
import org.mjsip.rtp.RtpPayloadFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.zoolu.net.UdpSocket;
import org.zoolu.sound.CodecType;
import org.zoolu.util.Encoder;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;

/**
 * mjSIP AudioTransmitter implementation for Nova Sonic
 */
public class NovaSonicAudioInput implements AudioTransmitter {
    private static final Logger LOG = LoggerFactory.getLogger(NovaSonicAudioInput.class);
    private final NovaS2SEventHandler handler;
    public NovaSonicAudioInput(NovaS2SEventHandler handler) {
        this.handler = handler;
    }


    @Override
    public AudioTXHandle createSender(RtpSenderOptions options, UdpSocket udp_socket, AudioFormat audio_format,
                                      CodecType codec, int payload_type, RtpPayloadFormat payloadFormat,
                                      int sample_rate, int channels, Encoder additional_encoder, long packet_time,
                                      int packet_size, String remote_addr, int remote_port,
                                      RtpStreamSenderListener listener, RtpControl rtpControl) throws IOException {
        LOG.info("Creating RTP stream sender with payloadType={} payloadFormat={} sampleRate={} channels={}", payload_type, payloadFormat, sample_rate, channels);
        RtpStreamSender sender = new RtpStreamSender(options, handler.getAudioInputStream(), true, payload_type, payloadFormat,
                sample_rate, channels, packet_time, packet_size, additional_encoder, udp_socket, remote_addr,
                remote_port, rtpControl, listener);
        return new RtpAudioTxHandle(sender);
    }
}
