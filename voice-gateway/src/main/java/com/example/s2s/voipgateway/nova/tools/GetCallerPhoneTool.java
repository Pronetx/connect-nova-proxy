package com.example.s2s.voipgateway.nova.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Tool for retrieving the caller's phone number.
 * Allows Nova to access and reference the caller's phone number in conversation.
 */
public class GetCallerPhoneTool implements Tool {
    private static final Logger log = LoggerFactory.getLogger(GetCallerPhoneTool.class);
    private final String phoneNumber;

    public GetCallerPhoneTool(String phoneNumber) {
        this.phoneNumber = phoneNumber;
    }

    @Override
    public String getName() {
        return "getCallerPhoneTool";
    }

    @Override
    public String getDescription() {
        return "Get the phone number of the current caller. Use this when you need to reference or verify the caller's phone number.";
    }

    @Override
    public Map<String, String> getInputSchema() {
        return ToolSpecs.DEFAULT_TOOL_SPEC;
    }

    @Override
    public void handle(String toolUseId, String content, Map<String, Object> output) throws Exception {
        log.info("Returning caller phone number: {}", phoneNumber);

        // Format phone number for better speech synthesis
        String formattedForSpeech = formatPhoneNumberForSpeech(phoneNumber);

        output.put("phoneNumber", phoneNumber);
        output.put("message", "The caller's phone number is " + formattedForSpeech);
    }

    /**
     * Format phone number for natural speech synthesis.
     * E.g., "+14435383548" -> "+1 4 4 3, 5 3 8, 3 5 4 8"
     * This helps TTS pronounce each digit individually with natural grouping.
     */
    private String formatPhoneNumberForSpeech(String phoneNumber) {
        // Remove any existing formatting
        String digitsOnly = phoneNumber.replaceAll("[^0-9+]", "");

        StringBuilder formatted = new StringBuilder();

        // Handle country code (+1)
        if (digitsOnly.startsWith("+1")) {
            formatted.append("+1 ");
            digitsOnly = digitsOnly.substring(2);
        } else if (digitsOnly.startsWith("1") && digitsOnly.length() == 11) {
            formatted.append("1 ");
            digitsOnly = digitsOnly.substring(1);
        }

        // Format remaining 10 digits as: X X X, X X X, X X X X
        // This groups them like area code, exchange, and line number
        if (digitsOnly.length() == 10) {
            // Area code: space between each digit
            formatted.append(digitsOnly.charAt(0)).append(" ")
                    .append(digitsOnly.charAt(1)).append(" ")
                    .append(digitsOnly.charAt(2)).append(", ");

            // Exchange: space between each digit
            formatted.append(digitsOnly.charAt(3)).append(" ")
                    .append(digitsOnly.charAt(4)).append(" ")
                    .append(digitsOnly.charAt(5)).append(", ");

            // Line number: space between each digit
            formatted.append(digitsOnly.charAt(6)).append(" ")
                    .append(digitsOnly.charAt(7)).append(" ")
                    .append(digitsOnly.charAt(8)).append(" ")
                    .append(digitsOnly.charAt(9));
        } else {
            // Fallback: just space out all digits
            for (int i = 0; i < digitsOnly.length(); i++) {
                if (i > 0) formatted.append(" ");
                formatted.append(digitsOnly.charAt(i));
            }
        }

        return formatted.toString();
    }
}
