package com.example.s2s.voipgateway.nova.tools;

import com.example.s2s.voipgateway.nova.event.PromptStartEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Constants for tool specifications.
 */
public class ToolSpecs {
    public static final Map<String,String> DEFAULT_TOOL_SPEC;
    public static final Map<String,String> OTP_VERIFY_TOOL_SPEC;

    static {
        Map<String, String> defaultToolSpec = new HashMap<>();
        try {
            defaultToolSpec.put("json",
                    new ObjectMapper().writeValueAsString(PromptStartEvent.ToolSchema.builder()
                            .type("object")
                            .properties(Collections.emptyMap())
                            .required(Collections.emptyList())
                            .build()));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize default tool schema!", e);
        }
        DEFAULT_TOOL_SPEC = Collections.unmodifiableMap(defaultToolSpec);

        // OTP verification tool schema with code parameter
        Map<String, String> otpVerifySpec = new HashMap<>();
        try {
            Map<String, Object> codeProperty = new HashMap<>();
            codeProperty.put("type", "string");
            codeProperty.put("description", "The 4-digit authentication code spoken by the caller");

            Map<String, Object> properties = new HashMap<>();
            properties.put("code", codeProperty);

            otpVerifySpec.put("json",
                    new ObjectMapper().writeValueAsString(PromptStartEvent.ToolSchema.builder()
                            .type("object")
                            .properties(properties)
                            .required(Collections.singletonList("code"))
                            .build()));
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize OTP verify tool schema!", e);
        }
        OTP_VERIFY_TOOL_SPEC = Collections.unmodifiableMap(otpVerifySpec);
    }
}
