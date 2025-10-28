package com.example.s2s.voipgateway.freeswitch;

import com.example.s2s.voipgateway.NovaMediaConfig;
import com.example.s2s.voipgateway.constants.MediaTypes;
import com.example.s2s.voipgateway.constants.SonicAudioConfig;
import com.example.s2s.voipgateway.constants.SonicAudioTypes;
import com.example.s2s.voipgateway.nova.NovaS2SBedrockInteractClient;
import com.example.s2s.voipgateway.nova.event.*;
import com.example.s2s.voipgateway.nova.observer.InteractObserver;
import com.example.s2s.voipgateway.nova.tools.ModularNovaS2SEventHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.http.Protocol;
import software.amazon.awssdk.http.ProtocolNegotiation;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;

import java.io.*;
import java.net.Socket;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

/**
 * Handles a single audio session from FreeSWITCH via TCP.
 *
 * This handler:
 * 1. Reads a handshake line with session ID and caller ID
 * 2. Connects to Nova Sonic
 * 3. Streams audio bidirectionally between FreeSWITCH and Nova
 *
 * Protocol:
 *   - Handshake: "NOVA_SESSION:<session_id>:CALLER:<caller_id>\n"
 *   - Then: Raw PCM audio bytes (8kHz, 16-bit, mono)
 */
public class FreeSwitchAudioHandler implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(FreeSwitchAudioHandler.class);
    private static final String ROLE_SYSTEM = "SYSTEM";

    private final Socket socket;
    private final NovaMediaConfig mediaConfig;
    private String sessionId;
    private String callerId;
    private volatile boolean active;

    public FreeSwitchAudioHandler(Socket socket, NovaMediaConfig mediaConfig) {
        this.socket = socket;
        this.mediaConfig = mediaConfig;
        this.active = true;
    }

    @Override
    public void run() {
        try {
            LOG.info("FreeSWITCH audio session started from {}", socket.getRemoteSocketAddress());

            // Read handshake
            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            String handshake = reader.readLine();

            if (handshake == null || !handshake.startsWith("NOVA_SESSION:")) {
                LOG.error("Invalid handshake from FreeSWITCH: {}", handshake);
                return;
            }

            // Parse handshake: "NOVA_SESSION:<uuid>:CALLER:<phone>"
            String[] parts = handshake.split(":");
            if (parts.length >= 4) {
                sessionId = parts[1];
                callerId = parts[3];
            } else {
                sessionId = UUID.randomUUID().toString();
                callerId = "Unknown";
            }

            LOG.info("Handshake received - Session: {}, Caller: {}", sessionId, callerId);

            // Initialize Nova Sonic connection (use same setup as NovaStreamerFactory)
            NettyNioAsyncHttpClient.Builder nettyBuilder = NettyNioAsyncHttpClient.builder()
                    .readTimeout(Duration.of(180, ChronoUnit.SECONDS))
                    .maxConcurrency(20)
                    .protocol(Protocol.HTTP2)
                    .protocolNegotiation(ProtocolNegotiation.ALPN);

            BedrockRuntimeAsyncClient client = BedrockRuntimeAsyncClient.builder()
                    .region(Region.US_EAST_1)
                    .httpClientBuilder(nettyBuilder)
                    .build();

            NovaS2SBedrockInteractClient novaClient = new NovaS2SBedrockInteractClient(
                    client,
                    "amazon.nova-sonic-v1:0"
            );

            // Create event handler
            ModularNovaS2SEventHandler eventHandler = new ModularNovaS2SEventHandler(callerId);
            eventHandler.setSessionId(sessionId);
            eventHandler.setHangupCallback(mediaConfig.getHangupCallback());

            String systemPrompt = mediaConfig.getNovaPrompt();
            LOG.info("Using system prompt: {}", systemPrompt);

            // Start Nova interaction
            InteractObserver<NovaSonicEvent> inputObserver = novaClient.interactMultimodal(
                    createSessionStartEvent(),
                    createPromptStartEvent(sessionId, eventHandler),
                    createSystemPrompt(sessionId, systemPrompt, callerId),
                    eventHandler);

            eventHandler.setOutbound(inputObserver);

            LOG.info("Nova Sonic streaming initialized for FreeSWITCH session {}", sessionId);

            // Start bidirectional audio streaming
            startAudioStreaming(socket, inputObserver, eventHandler);

            LOG.info("FreeSWITCH audio session ended: {}", sessionId);

        } catch (Exception e) {
            LOG.error("Error handling FreeSWITCH audio session", e);
        } finally {
            cleanup();
        }
    }

    /**
     * Streams audio bidirectionally between FreeSWITCH and Nova.
     */
    private void startAudioStreaming(Socket socket,
                                     InteractObserver<NovaSonicEvent> inputObserver,
                                     ModularNovaS2SEventHandler eventHandler) {

        // Thread 1: FreeSWITCH → Nova
        Thread freeswitchToNova = new Thread(() -> {
            try {
                InputStream socketInput = socket.getInputStream();
                Base64.Encoder encoder = Base64.getEncoder();
                byte[] buffer = new byte[320]; // 20ms at 8kHz, 16-bit
                String contentName = UUID.randomUUID().toString();
                boolean startSent = false;

                LOG.info("Starting FreeSWITCH → Nova audio stream");

                while (active && !socket.isClosed()) {
                    int bytesRead = socketInput.read(buffer);
                    if (bytesRead <= 0) {
                        LOG.info("FreeSWITCH audio stream ended");
                        break;
                    }

                    // Send StartAudioContent once at the beginning
                    if (!startSent) {
                        LOG.info("Sending StartAudioContent to Nova");
                        StartAudioContent startAudioContent = new StartAudioContent(
                                StartAudioContent.ContentStart.builder()
                                        .promptName(sessionId)
                                        .contentName(contentName)
                                        .type(StartAudioContent.TYPE_AUDIO)
                                        .interactive(true)
                                        .audioInputConfiguration(StartAudioContent.AudioInputConfiguration.builder()
                                                .mediaType(MediaTypes.AUDIO_LPCM)
                                                .sampleRateHertz(SonicAudioConfig.SAMPLE_RATE)
                                                .sampleSizeBits(SonicAudioConfig.SAMPLE_SIZE)
                                                .channelCount(SonicAudioConfig.CHANNEL_COUNT)
                                                .audioType(SonicAudioTypes.SPEECH)
                                                .encoding(SonicAudioConfig.ENCODING_BASE64)
                                                .build())
                                        .build());
                        inputObserver.onNext(startAudioContent);
                        startSent = true;
                    }

                    // Send audio chunks as AudioInputEvent
                    String base64Audio = encoder.encodeToString(buffer);
                    AudioInputEvent audioEvent = new AudioInputEvent(
                            AudioInputEvent.AudioInput.builder()
                                    .promptName(sessionId)
                                    .contentName(contentName)
                                    .role("USER")
                                    .content(base64Audio)
                                    .build());

                    inputObserver.onNext(audioEvent);
                }

                LOG.info("FreeSWITCH → Nova stream ended");

            } catch (Exception e) {
                LOG.error("Error in FreeSWITCH → Nova audio stream", e);
            }
        }, "FS-to-Nova-" + sessionId);

        // Thread 2: Nova → FreeSWITCH
        Thread novaToFreeswitch = new Thread(() -> {
            try {
                InputStream novaAudio = eventHandler.getAudioInputStream();
                OutputStream socketOutput = socket.getOutputStream();
                byte[] buffer = new byte[320];

                LOG.info("Starting Nova → FreeSWITCH audio stream");

                while (active && !socket.isClosed()) {
                    int bytesRead = novaAudio.read(buffer);
                    if (bytesRead <= 0) {
                        // No audio yet, sleep briefly
                        Thread.sleep(10);
                        continue;
                    }

                    // Send PCM audio back to FreeSWITCH
                    socketOutput.write(buffer, 0, bytesRead);
                    socketOutput.flush();
                }

                LOG.info("Nova → FreeSWITCH stream ended");

            } catch (Exception e) {
                LOG.error("Error in Nova → FreeSWITCH audio stream", e);
            }
        }, "Nova-to-FS-" + sessionId);

        // Start both threads
        freeswitchToNova.start();
        novaToFreeswitch.start();

        // Wait for both to complete
        try {
            freeswitchToNova.join();
            novaToFreeswitch.join();
        } catch (InterruptedException e) {
            LOG.error("Audio streaming interrupted", e);
        }
    }

    private SessionStartEvent createSessionStartEvent() {
        return new SessionStartEvent(
                mediaConfig.getNovaMaxTokens(),
                mediaConfig.getNovaTopP(),
                mediaConfig.getNovaTemperature());
    }

    private PromptStartEvent createPromptStartEvent(String promptName, ModularNovaS2SEventHandler eventHandler) {
        return new PromptStartEvent(PromptStartEvent.PromptStart.builder()
                .promptName(promptName)
                .textOutputConfiguration(MediaConfiguration.builder().mediaType(MediaTypes.TEXT_PLAIN).build())
                .audioOutputConfiguration(PromptStartEvent.AudioOutputConfiguration.builder()
                        .mediaType(MediaTypes.AUDIO_LPCM)
                        .sampleRateHertz(SonicAudioConfig.SAMPLE_RATE)
                        .sampleSizeBits(SonicAudioConfig.SAMPLE_SIZE)
                        .channelCount(SonicAudioConfig.CHANNEL_COUNT)
                        .voiceId(mediaConfig.getNovaVoiceId())
                        .encoding(SonicAudioConfig.ENCODING_BASE64)
                        .audioType(SonicAudioTypes.SPEECH)
                        .build())
                .toolConfiguration(eventHandler.getToolConfiguration())
                .build());
    }

    private TextInputEvent createSystemPrompt(String promptName, String systemPrompt, String callerPhone) {
        String enhancedPrompt = systemPrompt;
        if (callerPhone != null && !callerPhone.isEmpty() && !callerPhone.equals("Unknown")) {
            enhancedPrompt = systemPrompt + "\n\nThe caller's phone number is: " + callerPhone;
        }

        return new TextInputEvent(TextInputEvent.TextInput.builder()
                .promptName(promptName)
                .contentName(UUID.randomUUID().toString())
                .content(enhancedPrompt)
                .role(ROLE_SYSTEM)
                .build());
    }

    private void cleanup() {
        active = false;
        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            LOG.error("Error closing socket", e);
        }
    }
}
