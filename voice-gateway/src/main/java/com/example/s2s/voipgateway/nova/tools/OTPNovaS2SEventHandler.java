package com.example.s2s.voipgateway.nova.tools;

import com.example.s2s.voipgateway.nova.AbstractNovaS2SEventHandler;
import com.example.s2s.voipgateway.nova.event.PromptStartEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.pinpoint.PinpointClient;
import software.amazon.awssdk.services.pinpoint.model.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

/**
 * S2S Event Handler with OTP authentication via SMS.
 */
public class OTPNovaS2SEventHandler extends AbstractNovaS2SEventHandler {
    private static final Logger log = LoggerFactory.getLogger(OTPNovaS2SEventHandler.class);
    private static final String TIMEZONE = System.getenv().getOrDefault("TZ", "America/Los_Angeles");
    private final PinpointClient pinpointClient;
    private final String phoneNumber;
    private final Random random = new Random();
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Store OTP codes per session
    private final Map<String, String> otpStore = new ConcurrentHashMap<>();

    private Runnable hangupCallback;

    public OTPNovaS2SEventHandler(String phoneNumber) {
        this.phoneNumber = phoneNumber;

        // Initialize Pinpoint client for us-west-2
        String region = System.getenv().getOrDefault("AWS_REGION", "us-west-2");
        this.pinpointClient = PinpointClient.builder()
                .region(Region.of(region))
                .build();

        log.info("OTP Event Handler initialized with phone number: {} in region: {}", phoneNumber, region);
    }

    @Override
    protected void handleToolInvocation(String toolUseId, String toolName, String content, Map<String, Object> output) {
        if (toolName == null) {
            log.warn("Received null toolName");
            return;
        }

        log.info("Handling tool invocation: {} with content: {}", toolName, content);

        switch (toolName) {
            case "sendOTPTool":
                handleSendOTPTool(toolUseId, content, output);
                break;

            case "verifyOTPTool":
                handleVerifyOTPTool(toolUseId, content, output);
                break;

            case "hangupTool":
                handleHangupTool(output);
                break;

            default:
                log.warn("Unhandled tool: {}", toolName);
        }
    }

    @Override
    public PromptStartEvent.ToolConfiguration getToolConfiguration() {
        return PromptStartEvent.ToolConfiguration.builder()
                .tools(Arrays.asList(
                        PromptStartEvent.Tool.builder()
                                .toolSpec(PromptStartEvent.ToolSpec.builder()
                                        .name("sendOTPTool")
                                        .description("Generate and send a 4-digit authentication code via SMS to the caller's phone number. Use this when the caller requests an authentication token or OTP.")
                                        .inputSchema(ToolSpecs.DEFAULT_TOOL_SPEC)
                                        .build()).build(),
                        PromptStartEvent.Tool.builder()
                                .toolSpec(PromptStartEvent.ToolSpec.builder()
                                        .name("verifyOTPTool")
                                        .description("Verify the 4-digit authentication code provided by the caller against the code that was sent via SMS. The code parameter should be the 4-digit number spoken by the caller.")
                                        .inputSchema(ToolSpecs.OTP_VERIFY_TOOL_SPEC)
                                        .build()).build(),
                        PromptStartEvent.Tool.builder()
                                .toolSpec(PromptStartEvent.ToolSpec.builder()
                                        .name("hangupTool")
                                        .description("End the phone call when the conversation is complete or the caller requests to hang up")
                                        .inputSchema(ToolSpecs.DEFAULT_TOOL_SPEC)
                                        .build()).build()
                ))
                .build();
    }

    /**
     * Handles sending OTP via SMS.
     */
    private void handleSendOTPTool(String toolUseId, String content, Map<String, Object> output) {
        try {
            // Generate 4-digit OTP
            String otp = String.format("%04d", random.nextInt(10000));

            // Store OTP for this session
            otpStore.put(toolUseId, otp);

            log.info("Generated OTP: {} for toolUseId: {}", otp, toolUseId);

            // Send SMS via Pinpoint
            boolean smsSent = sendSMS(phoneNumber, "Your authentication code is: " + otp);

            if (smsSent) {
                output.put("status", "success");
                output.put("message", "Authentication code has been sent to your phone via SMS. Please wait to receive it and then repeat the 4-digit code.");
                output.put("sessionId", toolUseId);
                log.info("Successfully sent OTP to {}", phoneNumber);
            } else {
                output.put("status", "error");
                output.put("message", "Failed to send authentication code. Please try again.");
                log.error("Failed to send SMS to {}", phoneNumber);
            }

        } catch (Exception e) {
            log.error("Error handling sendOTPTool", e);
            output.put("status", "error");
            output.put("message", "An error occurred while sending the authentication code.");
        }
    }

    /**
     * Handles OTP verification.
     */
    private void handleVerifyOTPTool(String toolUseId, String content, Map<String, Object> output) {
        try {
            // Parse the code from content
            JsonNode contentNode = objectMapper.readTree(content);
            String providedCode = contentNode.has("code") ? contentNode.get("code").asText() : "";

            log.info("Verifying OTP. Provided code: {}", providedCode);

            // Normalize the code (remove spaces, hyphens, etc.)
            String normalizedCode = providedCode.replaceAll("[^0-9]", "");

            // Find the stored OTP for any previous session
            // In a real implementation, you'd want to associate this with the actual session
            String storedOTP = null;
            for (String otp : otpStore.values()) {
                storedOTP = otp;
                break; // Get the most recent one
            }

            if (storedOTP == null) {
                output.put("status", "error");
                output.put("verified", false);
                output.put("message", "No authentication code was sent. Please request a new code first.");
                log.warn("No OTP found in store");
                return;
            }

            log.info("Comparing provided code '{}' with stored OTP '{}'", normalizedCode, storedOTP);

            if (normalizedCode.equals(storedOTP)) {
                output.put("status", "success");
                output.put("verified", true);
                output.put("message", "Authentication successful! Your code is correct.");
                log.info("OTP verification successful");

                // Clear the OTP after successful verification
                otpStore.clear();
            } else {
                output.put("status", "error");
                output.put("verified", false);
                output.put("message", "Authentication failed. The code you provided does not match. Please try again.");
                log.warn("OTP verification failed. Expected: {}, Got: {}", storedOTP, normalizedCode);
            }

        } catch (Exception e) {
            log.error("Error handling verifyOTPTool", e);
            output.put("status", "error");
            output.put("verified", false);
            output.put("message", "An error occurred while verifying the code.");
        }
    }

    /**
     * Sends SMS via AWS Pinpoint.
     */
    private boolean sendSMS(String phoneNumber, String message) {
        try {
            Map<String, AddressConfiguration> addressMap = new HashMap<>();
            addressMap.put(phoneNumber, AddressConfiguration.builder()
                    .channelType(ChannelType.SMS)
                    .build());

            // Get origination phone number from environment or use default
            String originationNumber = System.getenv().getOrDefault("PINPOINT_ORIGINATION_NUMBER", "+13682104244");

            SMSMessage smsMessage = SMSMessage.builder()
                    .body(message)
                    .messageType(MessageType.TRANSACTIONAL)
                    .originationNumber(originationNumber)
                    .build();

            DirectMessageConfiguration directMessageConfiguration = DirectMessageConfiguration.builder()
                    .smsMessage(smsMessage)
                    .build();

            MessageRequest messageRequest = MessageRequest.builder()
                    .addresses(addressMap)
                    .messageConfiguration(directMessageConfiguration)
                    .build();

            // Note: Pinpoint requires an application ID (project ID)
            // This should be configured via environment variable
            String applicationId = System.getenv("PINPOINT_APPLICATION_ID");
            if (applicationId == null || applicationId.isEmpty()) {
                log.error("PINPOINT_APPLICATION_ID environment variable not set");
                return false;
            }

            SendMessagesRequest request = SendMessagesRequest.builder()
                    .applicationId(applicationId)
                    .messageRequest(messageRequest)
                    .build();

            SendMessagesResponse response = pinpointClient.sendMessages(request);

            MessageResponse messageResponse = response.messageResponse();
            Map<String, MessageResult> results = messageResponse.result();

            for (Map.Entry<String, MessageResult> entry : results.entrySet()) {
                MessageResult result = entry.getValue();
                log.info("SMS delivery status for {}: {}", entry.getKey(), result.deliveryStatus());

                if (result.deliveryStatus() == DeliveryStatus.SUCCESSFUL) {
                    return true;
                }
            }

            return false;

        } catch (Exception e) {
            log.error("Failed to send SMS via Pinpoint", e);
            return false;
        }
    }

    /**
     * Handles a request to hang up the call.
     */
    private void handleHangupTool(Map<String, Object> output) {
        log.info("Hangup tool invoked - will terminate call after goodbye");
        output.put("status", "acknowledged");
        output.put("message", "Goodbye");

        // Trigger hangup callback after giving Nova time to say goodbye
        if (hangupCallback != null) {
            new Thread(() -> {
                try {
                    Thread.sleep(3000); // Give Nova 3 seconds to say goodbye
                    log.info("Executing hangup callback to end call");
                    hangupCallback.run();
                } catch (Exception e) {
                    log.error("Failed to execute hangup callback", e);
                }
            }).start();
        } else {
            log.warn("No hangup callback configured - cannot terminate call");
        }
    }

    public void setHangupCallback(Runnable hangupCallback) {
        this.hangupCallback = hangupCallback;
    }

    @Override
    public void close() {
        if (pinpointClient != null) {
            pinpointClient.close();
        }
    }
}
