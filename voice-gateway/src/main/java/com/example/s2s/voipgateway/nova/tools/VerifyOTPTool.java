package com.example.s2s.voipgateway.nova.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Tool for verifying OTP codes.
 */
public class VerifyOTPTool implements Tool {
    private static final Logger log = LoggerFactory.getLogger(VerifyOTPTool.class);
    private final Map<String, String> otpStore;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public VerifyOTPTool(Map<String, String> otpStore) {
        this.otpStore = otpStore;
    }

    @Override
    public String getName() {
        return "verifyOTPTool";
    }

    @Override
    public String getDescription() {
        return "Verify the 4-digit authentication code provided by the caller against the code that was sent via SMS. The code parameter should be the 4-digit number spoken by the caller.";
    }

    @Override
    public Map<String, String> getInputSchema() {
        return ToolSpecs.OTP_VERIFY_TOOL_SPEC;
    }

    @Override
    public void handle(String toolUseId, String content, Map<String, Object> output) throws Exception {
        // Parse the code from content
        JsonNode contentNode = objectMapper.readTree(content);
        String providedCode = contentNode.has("code") ? contentNode.get("code").asText() : "";

        log.info("Verifying OTP. Provided code: {}", providedCode);

        // Normalize the code (remove spaces, hyphens, etc.)
        String normalizedCode = providedCode.replaceAll("[^0-9]", "");

        // Find the stored OTP for any previous session
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
    }
}
