package com.example.s2s.voipgateway.nova.tools;

import com.example.s2s.voipgateway.nova.event.PromptStartEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.pinpoint.PinpointClient;
import software.amazon.awssdk.services.pinpoint.model.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Generic tool for sending SMS messages via AWS Pinpoint.
 * Can send any text message to any phone number.
 */
public class SendSMSTool implements Tool {
    private static final Logger log = LoggerFactory.getLogger(SendSMSTool.class);
    private final PinpointClient pinpointClient;
    private final String callerPhoneNumber;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SendSMSTool(PinpointClient pinpointClient, String callerPhoneNumber) {
        this.pinpointClient = pinpointClient;
        this.callerPhoneNumber = callerPhoneNumber;
    }

    @Override
    public String getName() {
        return "sendSMSTool";
    }

    @Override
    public String getDescription() {
        return "Send an SMS text message to a phone number. Can send to the caller's number or any other phone number they provide. " +
               "Use this to send confirmations, information, or any text content the user requests via SMS.";
    }

    @Override
    public Map<String, String> getInputSchema() {
        Map<String, String> schema = new HashMap<>();
        try {
            // Build properties map
            Map<String, Object> properties = new HashMap<>();

            Map<String, Object> messageProp = new HashMap<>();
            messageProp.put("type", "string");
            messageProp.put("description", "The text message to send");
            properties.put("message", messageProp);

            Map<String, Object> phoneNumberProp = new HashMap<>();
            phoneNumberProp.put("type", "string");
            phoneNumberProp.put("description", "Phone number to send SMS to (include country code, e.g., +1234567890). If not provided, sends to caller's number.");
            properties.put("phoneNumber", phoneNumberProp);

            schema.put("json", objectMapper.writeValueAsString(
                    PromptStartEvent.ToolSchema.builder()
                            .type("object")
                            .properties(properties)
                            .required(Arrays.asList("message"))
                            .build()));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize SendSMSTool schema", e);
        }
        return schema;
    }

    @Override
    public void handle(String toolUseId, String content, Map<String, Object> output) throws Exception {
        try {
            log.info("SendSMSTool invoked with content: {}", content);

            // Parse input
            JsonNode jsonNode = objectMapper.readTree(content);

            String message = jsonNode.has("message") ? jsonNode.get("message").asText() : "";
            String phoneNumber = jsonNode.has("phoneNumber") ? jsonNode.get("phoneNumber").asText() : callerPhoneNumber;

            // Validate message
            if (message.isEmpty()) {
                output.put("status", "error");
                output.put("message", "No message content provided. Please specify what to send.");
                log.warn("No message content provided");
                return;
            }

            // Normalize phone number (remove formatting characters but keep +)
            String normalizedPhone = phoneNumber.replaceAll("[\\s()\\-.]", "");

            // Validate normalized phone number format (E.164 format: +[country code][number])
            if (!normalizedPhone.matches("^\\+?[1-9]\\d{1,14}$")) {
                output.put("status", "error");
                output.put("message", "Invalid phone number format. Please provide a valid phone number with country code.");
                log.warn("Invalid phone number format: {} (normalized: {})", phoneNumber, normalizedPhone);
                return;
            }

            log.info("Sending SMS to {} (normalized: {}) with message length: {}", phoneNumber, normalizedPhone, message.length());

            // Send SMS using normalized phone number
            boolean smsSent = sendSMS(normalizedPhone, message);

            if (smsSent) {
                output.put("status", "success");
                output.put("phoneNumber", normalizedPhone);
                output.put("message", String.format("SMS sent successfully to %s.", formatPhoneNumberForSpeech(phoneNumber)));
                log.info("SMS sent successfully to {} (normalized: {})", phoneNumber, normalizedPhone);
            } else {
                output.put("status", "error");
                output.put("message", "Failed to send SMS. There may be an issue with the messaging service.");
                log.error("Failed to send SMS to {}", phoneNumber);
            }

        } catch (Exception e) {
            log.error("Error in SendSMSTool", e);
            output.put("status", "error");
            output.put("message", "An error occurred while sending the SMS. Please try again.");
        }
    }

    /**
     * Formats phone number for speech output (digit by digit).
     */
    private String formatPhoneNumberForSpeech(String phoneNumber) {
        // Remove all non-digits for formatting
        String digits = phoneNumber.replaceAll("[^0-9]", "");

        // If it's the caller's number, just say "your number"
        if (phoneNumber.equals(callerPhoneNumber)) {
            return "your number";
        }

        // Otherwise format as digit-by-digit
        // E.g., "+14435383548" becomes "1-4-4-3-5-3-8-3-5-4-8"
        return String.join("-", digits.split(""));
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

            // Get origination phone number from environment
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
