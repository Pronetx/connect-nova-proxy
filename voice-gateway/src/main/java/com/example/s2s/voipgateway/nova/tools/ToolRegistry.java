package com.example.s2s.voipgateway.nova.tools;

import com.example.s2s.voipgateway.nova.event.PromptStartEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Central registry for managing Nova Sonic tools.
 * Provides a simple way to register and invoke tools.
 */
public class ToolRegistry {
    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    /**
     * Registers a tool in the registry.
     * @param tool The tool to register
     * @return this registry for chaining
     */
    public ToolRegistry register(Tool tool) {
        if (tool == null) {
            throw new IllegalArgumentException("Tool cannot be null");
        }
        String name = tool.getName();
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Tool name cannot be null or empty");
        }
        if (tools.containsKey(name)) {
            log.warn("Overwriting existing tool: {}", name);
        }
        tools.put(name, tool);
        log.info("Registered tool: {}", name);
        return this;
    }

    /**
     * Handles a tool invocation by delegating to the appropriate tool.
     * @param toolName The name of the tool to invoke
     * @param toolUseId The unique ID for this invocation
     * @param content The JSON content from Nova
     * @param output The output map to populate
     * @return true if the tool was found and handled, false otherwise
     */
    public boolean handle(String toolName, String toolUseId, String content, Map<String, Object> output) {
        Tool tool = tools.get(toolName);
        if (tool == null) {
            log.warn("No tool registered with name: {}", toolName);
            return false;
        }

        try {
            log.info("Invoking tool: {} with id: {}", toolName, toolUseId);
            tool.handle(toolUseId, content, output);
            return true;
        } catch (Exception e) {
            log.error("Error invoking tool: {}", toolName, e);
            output.put("status", "error");
            output.put("message", "An error occurred while executing the tool: " + e.getMessage());
            return false;
        }
    }

    /**
     * Gets the ToolConfiguration for Nova, including all registered tools.
     * @return ToolConfiguration with all tools
     */
    public PromptStartEvent.ToolConfiguration getToolConfiguration() {
        List<PromptStartEvent.Tool> toolList = tools.values().stream()
                .map(Tool::toPromptStartTool)
                .collect(Collectors.toList());

        return PromptStartEvent.ToolConfiguration.builder()
                .tools(toolList)
                .build();
    }

    /**
     * Gets all registered tool names.
     * @return Set of tool names
     */
    public Set<String> getToolNames() {
        return Collections.unmodifiableSet(tools.keySet());
    }

    /**
     * Gets a tool by name.
     * @param name The tool name
     * @return The tool, or null if not found
     */
    public Tool getTool(String name) {
        return tools.get(name);
    }

    /**
     * Clears all registered tools.
     */
    public void clear() {
        tools.clear();
    }
}
