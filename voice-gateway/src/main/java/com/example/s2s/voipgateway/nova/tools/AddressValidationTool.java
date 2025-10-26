package com.example.s2s.voipgateway.nova.tools;

import com.example.s2s.voipgateway.nova.event.PromptStartEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Tool for validating US addresses via SmartyStreets API through Lambda Function URL.
 * Provides conversational responses based on validation results.
 */
public class AddressValidationTool implements Tool {
    private static final Logger log = LoggerFactory.getLogger(AddressValidationTool.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient;
    private final String lambdaFunctionUrl;

    public AddressValidationTool() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        // Get Lambda Function URL from environment variable
        this.lambdaFunctionUrl = System.getenv().getOrDefault(
                "ADDRESS_VALIDATION_LAMBDA_URL",
                ""
        );

        if (lambdaFunctionUrl.isEmpty()) {
            log.warn("ADDRESS_VALIDATION_LAMBDA_URL not configured. Address validation will not work.");
        } else {
            log.info("AddressValidationTool initialized with Lambda URL: {}", lambdaFunctionUrl);
        }
    }

    @Override
    public String getName() {
        return "addressValidationTool";
    }

    @Override
    public String getDescription() {
        return "Validates a US address using SmartyStreets validation service. " +
               "Returns validation status and conversational guidance for the caller. " +
               "Use this after collecting address components to ensure the address is valid and deliverable.";
    }

    @Override
    public Map<String, String> getInputSchema() {
        Map<String, String> schema = new HashMap<>();
        try {
            // Build properties map
            Map<String, Object> properties = new HashMap<>();

            Map<String, Object> streetProp = new HashMap<>();
            streetProp.put("type", "string");
            streetProp.put("description", "Street address (e.g., '123 Main Street')");
            properties.put("street", streetProp);

            Map<String, Object> suiteProp = new HashMap<>();
            suiteProp.put("type", "string");
            suiteProp.put("description", "Apartment, suite, or unit number (optional)");
            properties.put("suite", suiteProp);

            Map<String, Object> cityProp = new HashMap<>();
            cityProp.put("type", "string");
            cityProp.put("description", "City name");
            properties.put("city", cityProp);

            Map<String, Object> stateProp = new HashMap<>();
            stateProp.put("type", "string");
            stateProp.put("description", "State abbreviation (e.g., 'MD', 'CA')");
            properties.put("state", stateProp);

            Map<String, Object> zipcodeProp = new HashMap<>();
            zipcodeProp.put("type", "string");
            zipcodeProp.put("description", "ZIP code (optional)");
            properties.put("zipcode", zipcodeProp);

            schema.put("json", objectMapper.writeValueAsString(
                    PromptStartEvent.ToolSchema.builder()
                            .type("object")
                            .properties(properties)
                            .required(Arrays.asList("street", "city", "state"))
                            .build()));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize AddressValidationTool schema", e);
        }
        return schema;
    }

    @Override
    public void handle(String toolUseId, String content, Map<String, Object> output) throws Exception {
        try {
            log.info("AddressValidationTool invoked with content: {}", content);

            // Check if Lambda URL is configured
            if (lambdaFunctionUrl.isEmpty()) {
                output.put("status", "error");
                output.put("message", "Address validation service is not configured.");
                log.error("ADDRESS_VALIDATION_LAMBDA_URL not configured");
                return;
            }

            // Parse input
            JsonNode jsonNode = objectMapper.readTree(content);

            String street = jsonNode.has("street") ? jsonNode.get("street").asText() : "";
            String suite = jsonNode.has("suite") ? jsonNode.get("suite").asText() : "";
            String city = jsonNode.has("city") ? jsonNode.get("city").asText() : "";
            String state = jsonNode.has("state") ? jsonNode.get("state").asText() : "";
            String zipcode = jsonNode.has("zipcode") ? jsonNode.get("zipcode").asText() : "";

            // Validate required fields
            if (street.isEmpty() || city.isEmpty() || state.isEmpty()) {
                output.put("status", "error");
                output.put("message", "Missing required fields. Street, city, and state are required.");
                log.warn("Missing required fields for address validation");
                return;
            }

            log.info("Validating address: {} {}, {}, {} {}",
                    street, suite, city, state, zipcode);

            // Build request payload
            Map<String, Object> requestPayload = new HashMap<>();
            requestPayload.put("street", street);
            if (!suite.isEmpty()) {
                requestPayload.put("suite", suite);
            }
            requestPayload.put("city", city);
            requestPayload.put("state", state);
            if (!zipcode.isEmpty()) {
                requestPayload.put("zipcode", zipcode);
            }
            requestPayload.put("candidates", 5);

            String requestBody = objectMapper.writeValueAsString(requestPayload);
            log.info("Sending validation request to Lambda: {}", requestBody);

            // Call Lambda Function URL
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(lambdaFunctionUrl))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            log.info("Lambda response status: {}", response.statusCode());
            log.info("Lambda response body: {}", response.body());

            if (response.statusCode() != 200) {
                output.put("status", "error");
                output.put("message", "Address validation service returned an error. Please try again.");
                log.error("Lambda returned non-200 status: {} - {}", response.statusCode(), response.body());
                return;
            }

            // Parse Lambda response
            JsonNode lambdaResponse = objectMapper.readTree(response.body());

            // Extract validation results
            String validationStatus = lambdaResponse.has("status") ?
                    lambdaResponse.get("status").asText() : "unknown";
            String conversationalResponse = lambdaResponse.has("conversationalResponse") ?
                    lambdaResponse.get("conversationalResponse").asText() :
                    "I had trouble validating that address.";

            // Build tool output
            output.put("status", validationStatus);
            output.put("message", conversationalResponse);

            // Include standardized address if available
            if (lambdaResponse.has("standardizedAddress")) {
                JsonNode standardized = lambdaResponse.get("standardizedAddress");
                Map<String, String> standardizedAddress = new HashMap<>();
                if (standardized.has("street")) {
                    standardizedAddress.put("street", standardized.get("street").asText());
                }
                if (standardized.has("suite")) {
                    standardizedAddress.put("suite", standardized.get("suite").asText());
                }
                if (standardized.has("city")) {
                    standardizedAddress.put("city", standardized.get("city").asText());
                }
                if (standardized.has("state")) {
                    standardizedAddress.put("state", standardized.get("state").asText());
                }
                if (standardized.has("zipcode")) {
                    standardizedAddress.put("zipcode", standardized.get("zipcode").asText());
                }
                output.put("standardizedAddress", standardizedAddress);
                log.info("Standardized address: {}", standardizedAddress);
            }

            // Include suggestions if available
            if (lambdaResponse.has("suggestions")) {
                output.put("suggestions", lambdaResponse.get("suggestions"));
                log.info("Multiple address suggestions returned");
            }

            // Include suggested action if available
            if (lambdaResponse.has("suggestedAction")) {
                output.put("suggestedAction", lambdaResponse.get("suggestedAction").asText());
            }

            log.info("Address validation completed with status: {}", validationStatus);

        } catch (IOException e) {
            log.error("Error calling address validation Lambda", e);
            output.put("status", "error");
            output.put("message", "I had trouble connecting to the address validation service. Please try again.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Address validation interrupted", e);
            output.put("status", "error");
            output.put("message", "The address validation was interrupted. Please try again.");
        } catch (Exception e) {
            log.error("Unexpected error in AddressValidationTool", e);
            output.put("status", "error");
            output.put("message", "An unexpected error occurred while validating the address. Please try again.");
        }
    }
}
