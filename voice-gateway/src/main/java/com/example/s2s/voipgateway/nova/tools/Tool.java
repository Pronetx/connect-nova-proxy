package com.example.s2s.voipgateway.nova.tools;

import com.example.s2s.voipgateway.nova.event.PromptStartEvent;

import java.util.Map;

/**
 * Interface for Nova Sonic tools.
 * Each tool defines its own schema, description, and handler logic.
 */
public interface Tool {
    /**
     * Gets the unique name of this tool.
     * @return Tool name (e.g., "sendOTPTool")
     */
    String getName();

    /**
     * Gets the description of what this tool does.
     * Nova uses this to decide when to invoke the tool.
     * @return Tool description
     */
    String getDescription();

    /**
     * Gets the input schema for this tool.
     * Defines what parameters Nova should pass to this tool.
     * @return Map containing the JSON schema
     */
    Map<String, String> getInputSchema();

    /**
     * Handles the tool invocation.
     * @param toolUseId The unique ID for this tool invocation
     * @param content The JSON content passed by Nova
     * @param output The output map to populate with results
     * @throws Exception if the tool execution fails
     */
    void handle(String toolUseId, String content, Map<String, Object> output) throws Exception;

    /**
     * Builds the PromptStartEvent.Tool for Nova configuration.
     * @return Configured Tool object
     */
    default PromptStartEvent.Tool toPromptStartTool() {
        return PromptStartEvent.Tool.builder()
                .toolSpec(PromptStartEvent.ToolSpec.builder()
                        .name(getName())
                        .description(getDescription())
                        .inputSchema(getInputSchema())
                        .build())
                .build();
    }
}
