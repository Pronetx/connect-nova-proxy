package com.example.s2s.voipgateway.nova.event;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonGetter;

import java.util.HashMap;
import java.util.Map;

/**
 * Nova event for tool result.
 */
public class ToolResultEvent implements NovaSonicEvent {
    private ToolResult toolResult = new ToolResult();

    public ToolResultEvent() {
    }

    public ToolResultEvent(ToolResult toolResult) {
        this.toolResult = toolResult;
    }

    @JsonGetter
    public ToolResult getToolResult() {
        return toolResult;
    }

    public void setToolResult(ToolResult toolResult) {
        this.toolResult = toolResult;
    }

    public static class ToolResult {
        private Map<String, Object> properties = new HashMap<>();

        public ToolResult() {
        }

        public ToolResult(Map<String, Object> properties) {
            this.properties = properties;
        }

        @JsonAnyGetter
        public Map<String, Object> getProperties() {
            return properties;
        }

        public void setProperties(Map<String, Object> properties) {
            this.properties = properties;
        }
    }
}
