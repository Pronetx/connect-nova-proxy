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
    }
}
