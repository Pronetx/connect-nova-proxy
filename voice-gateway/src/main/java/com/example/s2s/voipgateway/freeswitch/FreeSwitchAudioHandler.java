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
    private OutputStream socketOutput;

    /**
     * Represents session information parsed from handshake.
     */
    static class SessionInfo {
        String callUuid;
        String caller;
        int sampleRate;
        int channels;
        String format;
    }

    public FreeSwitchAudioHandler(Socket socket, NovaMediaConfig mediaConfig) {
        this.socket = socket;
        this.mediaConfig = mediaConfig;
        this.active = true;
    }

    @Override
    public void run() {
        try {
            LOG.info("FreeSWITCH audio session started from {}", socket.getRemoteSocketAddress());

            // Get raw streams - CRITICAL: Do NOT wrap with BufferedReader
            InputStream socketInput = socket.getInputStream();
            socketOutput = socket.getOutputStream();

            // Read handshake manually from raw stream (newline-delimited)
            ByteArrayOutputStream headerBuf = new ByteArrayOutputStream();
            while (true) {
                int b = socketInput.read();
                if (b == -1) {
                    LOG.error("Socket closed during handshake");
                    return;
                }
                if (b == '\n') break;
                headerBuf.write(b);
            }

            String handshake = headerBuf.toString("UTF-8");

            if (handshake == null) {
                LOG.error("Invalid handshake from FreeSWITCH: null");
                return;
            }

            SessionInfo sessionInfo;

            if (handshake.trim().startsWith("{")) {
                // New JSON handshake from FreeSWITCH
                try {
                    sessionInfo = parseJsonHandshake(handshake);
                } catch (Exception e) {
                    LOG.error("Failed to parse JSON handshake: {}", handshake, e);
                    return;
                }
            } else if (handshake.startsWith("NOVA_SESSION:")) {
                // Legacy colon-delimited handshake
                try {
                    sessionInfo = parseLegacyHandshake(handshake);
                } catch (Exception e) {
                    LOG.error("Failed to parse legacy handshake: {}", handshake, e);
                    return;
                }
            } else {
                LOG.error("Unrecognized handshake format from FreeSWITCH: {}", handshake);
                return;
            }

            sessionId = sessionInfo.callUuid;
            callerId = sessionInfo.caller;

            LOG.info("Handshake received - Session: {}, Caller: {}, SampleRate: {}, Channels: {}, Format: {}",
                    sessionId, callerId, sessionInfo.sampleRate, sessionInfo.channels, sessionInfo.format);

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

            // Store socket output for control messages
            socketOutput = socket.getOutputStream();

            // Create event handler with hangup callback that sends control message to FreeSWITCH
            ModularNovaS2SEventHandler eventHandler = new ModularNovaS2SEventHandler(callerId);
            eventHandler.setSessionId(sessionId);
            eventHandler.setHangupCallback(() -> {
                LOG.info("Nova requested hangup for session {}", sessionId);
                sendHangupControlMessage();
            });

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
     * Reads exactly 320 bytes from input stream, blocking until complete.
     * @return 320 if successful, -1 on EOF, or partial count if stream ends mid-frame
     */
    private int readFully320(InputStream in, byte[] frame) throws IOException {
        int off = 0;
        while (off < 320) {
            int r = in.read(frame, off, 320 - off);
            if (r < 0) return (off == 0 ? -1 : off); // EOF if nothing read
            off += r;
        }
        return 320;
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

                int totalBytesRead = 0;
                int chunkCount = 0;

                while (active && !socket.isClosed()) {
                    int bytesRead = socketInput.read(buffer);
                    if (bytesRead <= 0) {
                        LOG.info("FreeSWITCH audio stream ended (total: {} bytes in {} chunks)", totalBytesRead, chunkCount);
                        break;
                    }

                    totalBytesRead += bytesRead;
                    chunkCount++;

                    // Log every 50th chunk (every second at 20ms chunks)
                    if (chunkCount % 50 == 0) {
                        LOG.info("FreeSWITCH → Nova: received {} chunks, {} total bytes", chunkCount, totalBytesRead);
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

                    // Log first audio chunk sent
                    if (chunkCount == 1) {
                        LOG.info("First AudioInputEvent sent to Nova ({} bytes)", bytesRead);
                    }
                }

                LOG.info("FreeSWITCH → Nova stream ended");

            } catch (Exception e) {
                LOG.error("Error in FreeSWITCH → Nova audio stream", e);
            }
        }, "FS-to-Nova-" + sessionId);

        // Thread 2: Nova → FreeSWITCH (PCM16, 320-byte frames)
        Thread novaToFreeswitch = new Thread(() -> {
            try {
                InputStream novaAudio = eventHandler.getAudioInputStream();
                OutputStream socketOutput = socket.getOutputStream();
                socket.setTcpNoDelay(true);
                byte[] frame = new byte[320]; // Exact 20ms PCM16 frame
                int framesWritten = 0;

                LOG.info("Starting Nova → FreeSWITCH audio stream (PCM16, 320-byte frames)");

                while (active && !socket.isClosed()) {
                    int n = readFully320(novaAudio, frame);

                    if (n < 0) {
                        // No audio yet / EOF
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException ignored) {}
                        continue;
                    }

                    // Write exactly 320 bytes per 20ms frame
                    socketOutput.write(frame);
                    socketOutput.flush();
                    framesWritten++;

                    // Log every 50 frames (every second at 20ms/frame)
                    if (framesWritten % 50 == 0) {
                        LOG.info("Nova → FS: wrote {} frames ({} bytes)", framesWritten, framesWritten * 320);
                    }
                }

                LOG.info("Nova → FreeSWITCH stream ended ({} total frames written)", framesWritten);

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

    /**
     * Sends a hangup control message to FreeSWITCH.
     * Control messages are 4-byte length-prefixed JSON payloads.
     */
    private void sendHangupControlMessage() {
        try {
            String controlMessage = "{\"type\":\"hangup\"}";
            byte[] messageBytes = controlMessage.getBytes("UTF-8");

            // Send length prefix (4 bytes, big-endian)
            byte[] lengthPrefix = new byte[4];
            lengthPrefix[0] = (byte) ((messageBytes.length >> 24) & 0xFF);
            lengthPrefix[1] = (byte) ((messageBytes.length >> 16) & 0xFF);
            lengthPrefix[2] = (byte) ((messageBytes.length >> 8) & 0xFF);
            lengthPrefix[3] = (byte) (messageBytes.length & 0xFF);

            synchronized (socketOutput) {
                socketOutput.write(lengthPrefix);
                socketOutput.write(messageBytes);
                socketOutput.flush();
            }

            LOG.info("Sent hangup control message to FreeSWITCH: {}", controlMessage);

            // Give FreeSWITCH time to process the hangup
            Thread.sleep(500);

        } catch (Exception e) {
            LOG.error("Failed to send hangup control message", e);
        }
    }

    /**
     * Parses JSON handshake format.
     * Expected format: {"call_uuid":"...", "caller":"...", "sample_rate":8000, "channels":1, "format":"PCM16"}
     */
    private SessionInfo parseJsonHandshake(String json) throws Exception {
        SessionInfo info = new SessionInfo();

        // Remove whitespace
        String body = json.trim();

        // Extract JSON values
        info.callUuid   = extractJsonString(body, "call_uuid");
        info.caller     = extractJsonString(body, "caller");
        info.format     = extractJsonString(body, "format");

        String srStr    = extractJsonNumber(body, "sample_rate");
        String chStr    = extractJsonNumber(body, "channels");

        info.sampleRate = srStr != null ? Integer.parseInt(srStr) : 8000;
        info.channels   = chStr != null ? Integer.parseInt(chStr) : 1;

        // Defaults
        if (info.callUuid == null) {
            info.callUuid = UUID.randomUUID().toString();
        }
        if (info.caller == null) {
            info.caller = "Unknown";
        }
        if (info.format == null) {
            info.format = "PCM16";
        }

        return info;
    }

    /**
     * Parses legacy colon-delimited handshake format.
     * Expected format: NOVA_SESSION:<uuid>:CALLER:<caller>[:SR:<rate>:CH:<ch>:FORMAT:<fmt>]
     */
    private SessionInfo parseLegacyHandshake(String hs) {
        SessionInfo info = new SessionInfo();
        String[] parts = hs.trim().split(":");

        // Basic parsing
        // [0] NOVA_SESSION
        // [1] <uuid>
        // [2] CALLER
        // [3] <caller>
        info.callUuid = parts.length > 1 ? parts[1] : UUID.randomUUID().toString();
        info.caller   = parts.length > 3 ? parts[3] : "Unknown";

        // Optional extras
        for (int i = 4; i < parts.length - 1; i += 2) {
            String k = parts[i];
            String v = parts[i + 1];
            if ("SR".equalsIgnoreCase(k)) {
                info.sampleRate = Integer.parseInt(v);
            } else if ("CH".equalsIgnoreCase(k)) {
                info.channels = Integer.parseInt(v);
            } else if ("FORMAT".equalsIgnoreCase(k)) {
                info.format = v;
            }
        }

        // Defaults
        if (info.sampleRate == 0) info.sampleRate = 8000;
        if (info.channels == 0) info.channels = 1;
        if (info.format == null) info.format = "PCM16";

        return info;
    }

    /**
     * Extracts a string value from JSON for a given key.
     * Very naive matcher: "key": "value"
     */
    private String extractJsonString(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return null;
        int colon = json.indexOf(":", idx);
        if (colon < 0) return null;
        int firstQuote = json.indexOf("\"", colon);
        int secondQuote = json.indexOf("\"", firstQuote + 1);
        if (firstQuote < 0 || secondQuote < 0) return null;
        return json.substring(firstQuote + 1, secondQuote);
    }

    /**
     * Extracts a numeric value from JSON for a given key.
     * Matches something like "sample_rate": 8000
     */
    private String extractJsonNumber(String json, String key) {
        String pattern = "\"" + key + "\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return null;
        int colon = json.indexOf(":", idx);
        if (colon < 0) return null;
        // Skip colon and any spaces
        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        // Read until comma or }
        int end = start;
        while (end < json.length() && "0123456789".indexOf(json.charAt(end)) >= 0) end++;
        if (end == start) return null;
        return json.substring(start, end);
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
