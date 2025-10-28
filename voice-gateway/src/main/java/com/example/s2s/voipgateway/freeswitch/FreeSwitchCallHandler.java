package com.example.s2s.voipgateway.freeswitch;

import com.example.s2s.voipgateway.NovaMediaConfig;
import com.example.s2s.voipgateway.constants.MediaTypes;
import com.example.s2s.voipgateway.constants.SonicAudioConfig;
import com.example.s2s.voipgateway.constants.SonicAudioTypes;
import com.example.s2s.voipgateway.nova.*;
import com.example.s2s.voipgateway.nova.event.*;
import com.example.s2s.voipgateway.nova.observer.InteractObserver;
import com.example.s2s.voipgateway.nova.tools.ModularNovaS2SEventHandler;
import com.example.s2s.voipgateway.nova.tools.PromptConfiguration;
import com.example.s2s.voipgateway.recording.CallRecorder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.http.Protocol;
import software.amazon.awssdk.http.ProtocolNegotiation;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.s3.S3Client;

import java.io.*;
import java.net.DatagramSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Handles a single call from FreeSWITCH via Event Socket outbound protocol.
 * Receives events, commands FreeSWITCH, and streams audio to/from Nova Sonic.
 */
public class FreeSwitchCallHandler implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(FreeSwitchCallHandler.class);
    private static final String ROLE_SYSTEM = "SYSTEM";

    private final Socket socket;
    private final NovaMediaConfig mediaConfig;
    private BufferedReader reader;
    private PrintWriter writer;
    private Map<String, String> channelData;
    private volatile boolean callActive;

    public FreeSwitchCallHandler(Socket socket, NovaMediaConfig mediaConfig) {
        this.socket = socket;
        this.mediaConfig = mediaConfig;
        this.channelData = new HashMap<>();
        this.callActive = false;
    }

    @Override
    public void run() {
        try {
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            writer = new PrintWriter(socket.getOutputStream(), true);

            // In Event Socket outbound mode with "async full", we need to send "connect" to get channel data
            LOG.info("Sending connect command to get channel data");
            writer.println("connect");
            writer.println();
            writer.flush();

            // Read the response which contains all channel variables
            readEvent();

            LOG.info("Initial event read with {} headers", channelData.size());

            // Event Socket outbound uses Channel-* headers for main channel info
            String callerId = channelData.getOrDefault("Channel-Caller-ID-Number",
                            channelData.getOrDefault("Caller-Caller-ID-Number", "Unknown"));
            String calledNumber = channelData.getOrDefault("Channel-Destination-Number",
                            channelData.getOrDefault("Caller-Destination-Number", "Unknown"));
            String channelUuid = channelData.getOrDefault("Channel-Unique-ID",
                            channelData.getOrDefault("Unique-ID", "unknown"));

            LOG.info("Call from {} to {}, UUID: {}", callerId, calledNumber, channelUuid);

            // Store caller info in media config
            mediaConfig.setCallerPhoneNumber(callerId);
            mediaConfig.setCalledNumber(calledNumber);

            // Answer the call
            sendCommand("answer");
            callActive = true;

            // Set up hangup callback that will trigger FreeSWITCH hangup
            mediaConfig.setHangupCallback(() -> {
                LOG.info("Hangup callback invoked for call {}", channelUuid);
                sendCommand("hangup");
                callActive = false;
            });

            // Log that we're starting Nova integration
            LOG.info("Starting Nova Sonic integration for call {}", channelUuid);

            // Initialize Nova Sonic streaming
            setupNovaStreaming(channelUuid, callerId, calledNumber);

            // Keep connection alive while call is active
            while (callActive && !socket.isClosed()) {
                Thread.sleep(1000);
            }

        } catch (Exception e) {
            LOG.error("Error handling call", e);
        } finally {
            cleanup();
        }
    }

    private void readEvent() throws IOException {
        channelData.clear();
        String line;

        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                // Empty line marks end of event
                break;
            }

            // Parse header: Key: Value
            int colonIndex = line.indexOf(':');
            if (colonIndex > 0) {
                String key = line.substring(0, colonIndex).trim();
                String value = line.substring(colonIndex + 1).trim();

                // URL-decode the value (FreeSWITCH Event Socket sends URL-encoded values)
                try {
                    value = URLDecoder.decode(value, StandardCharsets.UTF_8);
                } catch (Exception e) {
                    LOG.debug("Failed to URL-decode value for key {}: {}", key, value);
                }

                channelData.put(key, value);
                LOG.info("Event header: {} = {}", key, value);
            }
        }

        LOG.info("Read event with {} headers. Caller-ID: {}, Destination: {}, UUID: {}",
                channelData.size(),
                channelData.get("Channel-Caller-ID-Number"),
                channelData.get("Channel-Destination-Number"),
                channelData.get("Channel-Unique-ID"));
    }

    private void sendCommand(String command) {
        LOG.debug("Sending command: {}", command);
        writer.println("sendmsg");
        writer.println("call-command: execute");
        writer.println("execute-app-name: " + command.split(" ")[0]);
        if (command.contains(" ")) {
            writer.println("execute-app-arg: " + command.substring(command.indexOf(" ") + 1));
        }
        writer.println(); // Empty line to end command
        writer.flush();

        try {
            // Read command response
            readEvent();
        } catch (IOException e) {
            LOG.error("Error reading command response", e);
        }
    }

    /**
     * Initializes Nova Sonic streaming for the call.
     * Creates Bedrock client, event handlers, and starts bidirectional audio streaming.
     */
    private void setupNovaStreaming(String channelUuid, String callerId, String calledNumber) {
        try {
            // Create Bedrock client
            NettyNioAsyncHttpClient.Builder nettyBuilder = NettyNioAsyncHttpClient.builder()
                    .readTimeout(Duration.of(180, ChronoUnit.SECONDS))
                    .maxConcurrency(20)
                    .protocol(Protocol.HTTP2)
                    .protocolNegotiation(ProtocolNegotiation.ALPN);

            BedrockRuntimeAsyncClient client = BedrockRuntimeAsyncClient.builder()
                    .region(Region.US_EAST_1)
                    .httpClientBuilder(nettyBuilder)
                    .build();

            String sessionId = UUID.randomUUID().toString();
            LOG.info("Creating Nova session with ID: {}", sessionId);

            NovaS2SBedrockInteractClient novaClient = new NovaS2SBedrockInteractClient(client, "amazon.nova-sonic-v1:0");

            // Select prompt based on caller/called number
            String promptConfigPath = com.example.s2s.voipgateway.nova.tools.PromptSelector.selectPrompt(
                    callerId, calledNumber);
            PromptConfiguration promptConfig = null;
            String systemPrompt = mediaConfig.getNovaPrompt();

            try {
                promptConfig = PromptConfiguration.fromResource(promptConfigPath);
                systemPrompt = promptConfig.getSystemPrompt();
                LOG.info("Loaded prompt configuration from: {}", promptConfigPath);
                LOG.info("Enabled tools: {}", promptConfig.getToolNames());
            } catch (Exception e) {
                LOG.warn("Failed to load prompt configuration from {}: {}. Using default configuration.",
                        promptConfigPath, e.getMessage());
            }

            // Create event handler with prompt config if available
            ModularNovaS2SEventHandler eventHandler;
            if (promptConfig != null) {
                eventHandler = new ModularNovaS2SEventHandler(callerId, promptConfig);
            } else {
                eventHandler = new ModularNovaS2SEventHandler(callerId);
            }

            eventHandler.setSessionId(sessionId);
            eventHandler.setHangupCallback(mediaConfig.getHangupCallback());

            LOG.info("Using system prompt: {}", systemPrompt);

            // Initialize call recorder if enabled
            CallRecorder callRecorder = null;
            String recordingBucket = System.getenv().getOrDefault("CALL_RECORDING_BUCKET", "");
            boolean recordingEnabled = !recordingBucket.isEmpty();

            if (recordingEnabled) {
                LOG.info("Call recording enabled - bucket: {}", recordingBucket);
                S3Client s3Client = S3Client.builder()
                        .region(Region.US_WEST_2)
                        .build();
                callRecorder = new CallRecorder(sessionId, callerId, s3Client, recordingBucket);
                LOG.info("CallRecorder initialized for session: {}", sessionId);
            } else {
                LOG.info("Call recording disabled - set CALL_RECORDING_BUCKET environment variable to enable");
            }

            // Start Nova interaction
            InteractObserver<NovaSonicEvent> inputObserver = novaClient.interactMultimodal(
                    createSessionStartEvent(),
                    createPromptStartEvent(sessionId, eventHandler),
                    createSystemPrompt(sessionId, systemPrompt, callerId),
                    eventHandler);

            eventHandler.setOutbound(inputObserver);

            // Inject call recorder into event handler for upload on call completion
            if (callRecorder != null) {
                eventHandler.setCallRecorder(callRecorder);
            }

            LOG.info("Nova Sonic streaming initialized successfully for call {}", channelUuid);

            // Start audio streaming threads
            startAudioStreaming(channelUuid, inputObserver, eventHandler, callRecorder);

        } catch (Exception e) {
            LOG.error("Failed to setup Nova streaming for call {}", channelUuid, e);
            throw new RuntimeException("Nova streaming setup failed", e);
        }
    }

    /**
     * Creates the SessionStart event for Nova.
     */
    private SessionStartEvent createSessionStartEvent() {
        return new SessionStartEvent(
                mediaConfig.getNovaMaxTokens(),
                mediaConfig.getNovaTopP(),
                mediaConfig.getNovaTemperature());
    }

    /**
     * Creates the PromptStart event for Nova with tool configuration.
     */
    private PromptStartEvent createPromptStartEvent(String promptName, NovaS2SEventHandler eventHandler) {
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
                .toolUseOutputConfiguration(MediaConfiguration.builder().mediaType(MediaTypes.APPLICATION_JSON).build())
                .toolConfiguration(eventHandler.getToolConfiguration())
                .build());
    }

    /**
     * Creates the system prompt for Nova with caller phone number context.
     */
    private TextInputEvent createSystemPrompt(String promptName, String systemPrompt, String callerPhone) {
        String enhancedPrompt = systemPrompt;
        if (callerPhone != null && !callerPhone.isEmpty() && !callerPhone.equals("Unknown")) {
            enhancedPrompt = systemPrompt + "\n\nThe caller's phone number is: " + callerPhone +
                    " When the caller asks you to repeat their phone number, this is the number you would repeat.";
            LOG.info("Enhanced system prompt with caller phone number: {}", callerPhone);
        }

        return new TextInputEvent(TextInputEvent.TextInput.builder()
                .promptName(promptName)
                .contentName(UUID.randomUUID().toString())
                .content(enhancedPrompt)
                .role(ROLE_SYSTEM)
                .build());
    }

    /**
     * Starts bidirectional audio streaming between FreeSWITCH and Nova.
     *
     * Uses FreeSWITCH unicast to stream RTP directly to our Java application.
     * Audio flows: FreeSWITCH RTP → Java UDP → Nova Sonic → Java → FreeSWITCH (via playback)
     */
    private void startAudioStreaming(String channelUuid,
                                     InteractObserver<NovaSonicEvent> inputObserver,
                                     ModularNovaS2SEventHandler eventHandler,
                                     CallRecorder callRecorder) {

        LOG.info("Setting up audio streaming for channel {}", channelUuid);

        try {
            // Create a UDP socket to receive RTP audio from FreeSWITCH
            DatagramSocket rtpSocket = new DatagramSocket();
            int rtpPort = rtpSocket.getLocalPort();
            LOG.info("Created RTP receiver on port {}", rtpPort);

            // Tell FreeSWITCH to unicast RTP to our socket
            // Format: unicast <local_ip:local_port> [<remote_ip:remote_port>] <transport> [<flags>]
            String unicastCommand = String.format("api uuid_buglist %s", channelUuid);
            sendCommand(unicastCommand);

            // Actually, let's use a simpler approach with the echo app first to test
            // Then upgrade to unicast

            // For MVP: Just answer and park the call, use file-based approach
            sendCommand("sendmsg");
            sendCommand("call-command: execute");
            sendCommand("execute-app-name: park");
            sendCommand("");

            LOG.info("Call parked, audio streaming placeholder active");

            // NOTE: FreeSWITCH audio integration requires either:
            // 1. Using unicast + RTP library (complex)
            // 2. Using external media server (Asterisk, Kamailio)
            // 3. Using file-based record/playback (not real-time)
            // 4. Custom FreeSWITCH module (C/C++)
            //
            // For now, we've established the Event Socket connection and can control the call.
            // Audio integration is the next step and requires more infrastructure.

        } catch (Exception e) {
            LOG.error("Error setting up audio streaming", e);
        }
    }

    private void cleanup() {
        callActive = false;
        try {
            if (reader != null) reader.close();
            if (writer != null) writer.close();
            if (socket != null && !socket.isClosed()) socket.close();
        } catch (IOException e) {
            LOG.error("Error cleaning up", e);
        }
    }
}
