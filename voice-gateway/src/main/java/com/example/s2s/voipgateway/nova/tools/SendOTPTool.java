package com.example.s2s.voipgateway.nova.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.pinpoint.PinpointClient;
import software.amazon.awssdk.services.pinpoint.model.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;

/**
 * Tool for generating and sending OTP codes via SMS.
 */
public class SendOTPTool implements Tool {
    private static final Logger log = LoggerFactory.getLogger(SendOTPTool.class);
    private final PinpointClient pinpointClient;
    private final String phoneNumber;
    private final Random random = new Random();
    private final Map<String, String> otpStore;

    public SendOTPTool(PinpointClient pinpointClient, String phoneNumber, Map<String, String> otpStore) {
        this.pinpointClient = pinpointClient;
        this.phoneNumber = phoneNumber;
        this.otpStore = otpStore;
    }

    @Override
    public String getName() {
        return "sendOTPTool";
    }

    @Override
    public String getDescription() {
        return "Generate and send a 4-digit authentication code via SMS to the caller's phone number. Use this when the caller requests an authentication token or OTP.";
    }

    @Override
    public Map<String, String> getInputSchema() {
        return ToolSpecs.DEFAULT_TOOL_SPEC;
    }

    @Override
    public void handle(String toolUseId, String content, Map<String, Object> output) throws Exception {
        // Generate 4-digit OTP
        String otp = String.format("%04d", random.nextInt(10000));

        // Clear any previous OTPs and store the new one
        otpStore.clear();
        otpStore.put(toolUseId, otp);

        log.info("Generated OTP: {} for toolUseId: {}", otp, toolUseId);

        // Send SMS asynchronously to avoid blocking audio stream
        CompletableFuture.runAsync(() -> {
            try {
                boolean smsSent = sendSMS(phoneNumber, "Your authentication code is: " + otp);
                if (smsSent) {
                    log.info("Successfully sent OTP to {} (async)", phoneNumber);
                } else {
                    log.error("Failed to send SMS to {} (async)", phoneNumber);
                }
            } catch (Exception e) {
                log.error("Error sending SMS asynchronously", e);
            }
        });

        // Return immediately without blocking
        output.put("status", "success");
        output.put("message", "SMS sent successfully. Now tell the caller: 'The code should arrive in a few seconds. Please read me the four digits when you receive it.'");
        output.put("sessionId", toolUseId);
        log.info("Returning from sendOTPTool immediately (SMS sending in background)");
    }

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
}
