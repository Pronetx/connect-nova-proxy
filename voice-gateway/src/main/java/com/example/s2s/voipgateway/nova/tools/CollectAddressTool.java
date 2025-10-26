package com.example.s2s.voipgateway.nova.tools;

import com.example.s2s.voipgateway.nova.event.PromptStartEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Tool for collecting and confirming caller's address.
 * Focuses solely on address collection and validation.
 * Use SendSMSTool separately if caller wants SMS confirmation.
 */
public class CollectAddressTool implements Tool {
    private static final Logger log = LoggerFactory.getLogger(CollectAddressTool.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String getName() {
        return "collectAddressTool";
    }

    @Override
    public String getDescription() {
        return "Collect and confirm the caller's complete address including street, suite/apt (if present), city, state, and zipcode. " +
               "Returns the formatted address after caller confirmation. Use SendSMSTool separately if the caller wants SMS confirmation.";
    }

    @Override
    public Map<String, String> getInputSchema() {
        Map<String, String> schema = new HashMap<>();
        try {
            // Build properties map
            Map<String, Object> properties = new HashMap<>();

            Map<String, Object> streetProp = new HashMap<>();
            streetProp.put("type", "string");
            streetProp.put("description", "Street address");
            properties.put("street", streetProp);

            Map<String, Object> suiteProp = new HashMap<>();
            suiteProp.put("type", "string");
            suiteProp.put("description", "Suite, apartment, or unit number (optional)");
            properties.put("suite", suiteProp);

            Map<String, Object> cityProp = new HashMap<>();
            cityProp.put("type", "string");
            cityProp.put("description", "City");
            properties.put("city", cityProp);

            Map<String, Object> stateProp = new HashMap<>();
            stateProp.put("type", "string");
            stateProp.put("description", "State (2-letter code or full name)");
            properties.put("state", stateProp);

            Map<String, Object> zipcodeProp = new HashMap<>();
            zipcodeProp.put("type", "string");
            zipcodeProp.put("description", "ZIP code");
            properties.put("zipcode", zipcodeProp);

            Map<String, Object> confirmedProp = new HashMap<>();
            confirmedProp.put("type", "boolean");
            confirmedProp.put("description", "Whether caller confirmed the address is correct");
            properties.put("confirmed", confirmedProp);

            schema.put("json", objectMapper.writeValueAsString(
                    PromptStartEvent.ToolSchema.builder()
                            .type("object")
                            .properties(properties)
                            .required(Arrays.asList("street", "city", "state", "zipcode", "confirmed"))
                            .build()));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize CollectAddressTool schema", e);
        }
        return schema;
    }

    @Override
    public void handle(String toolUseId, String content, Map<String, Object> output) throws Exception {
        try {
            log.info("CollectAddressTool invoked with content: {}", content);

            // Parse the address data from Nova
            JsonNode jsonNode = objectMapper.readTree(content);

            String street = jsonNode.has("street") ? jsonNode.get("street").asText() : "";
            String suite = jsonNode.has("suite") ? jsonNode.get("suite").asText() : "";
            String city = jsonNode.has("city") ? jsonNode.get("city").asText() : "";
            String state = jsonNode.has("state") ? jsonNode.get("state").asText() : "";
            String zipcode = jsonNode.has("zipcode") ? jsonNode.get("zipcode").asText() : "";
            boolean confirmed = jsonNode.has("confirmed") && jsonNode.get("confirmed").asBoolean();

            // Validate required fields
            if (street.isEmpty() || city.isEmpty() || state.isEmpty() || zipcode.isEmpty()) {
                output.put("status", "error");
                output.put("message", "Missing required address fields. Please provide street, city, state, and zipcode.");
                log.warn("Missing required address fields");
                return;
            }

            if (!confirmed) {
                output.put("status", "error");
                output.put("message", "Address was not confirmed by the caller. Please confirm before proceeding.");
                log.info("Address not confirmed by caller");
                return;
            }

            // Format the complete address
            String fullAddress = formatAddress(street, suite, city, state, zipcode);
            log.info("Collected and confirmed address: {}", fullAddress);

            output.put("status", "success");
            output.put("address", fullAddress);
            output.put("street", street);
            output.put("suite", suite);
            output.put("city", city);
            output.put("state", state);
            output.put("zipcode", zipcode);
            output.put("message", "Address confirmed and recorded successfully. If the caller wants an SMS, use the SendSMSTool with this address.");
            log.info("Address confirmed: {}", fullAddress);

        } catch (Exception e) {
            log.error("Error in CollectAddressTool", e);
            output.put("status", "error");
            output.put("message", "There was an error processing the address. Please try again.");
        }
    }

    /**
     * Formats the address components into a single string.
     */
    private String formatAddress(String street, String suite, String city, String state, String zipcode) {
        StringBuilder address = new StringBuilder(street);

        if (suite != null && !suite.isEmpty()) {
            address.append(", ").append(suite);
        }

        address.append(", ").append(city)
               .append(", ").append(state)
               .append(" ").append(zipcode);

        return address.toString();
    }
}
