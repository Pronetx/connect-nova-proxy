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

        output.put("phoneNumber", phoneNumber);
        output.put("message", "The caller's phone number is " + phoneNumber);
    }
}
