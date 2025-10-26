package com.example.s2s.voipgateway.nova.tools;

import com.example.s2s.voipgateway.nova.AbstractNovaS2SEventHandler;
import com.example.s2s.voipgateway.nova.event.PromptStartEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Modular S2S Event Handler with auto-discovery of tools.
 * Tools are automatically discovered from the tools package - just add a new Tool implementation
 * and it will be available. Tools can be filtered via PromptConfiguration files.
 */
public class ModularNovaS2SEventHandler extends AbstractNovaS2SEventHandler {
    private static final Logger log = LoggerFactory.getLogger(ModularNovaS2SEventHandler.class);
    private final ToolRegistry toolRegistry;

    /**
     * Creates a handler with all auto-discovered tools enabled.
     * @param phoneNumber The caller's phone number for SMS
     */
    public ModularNovaS2SEventHandler(String phoneNumber) {
        this.toolRegistry = new ToolRegistry();
        initializeAllTools(phoneNumber);
    }

    /**
     * Creates a handler with tools specified in PromptConfiguration.
     * @param phoneNumber The caller's phone number for SMS
     * @param promptConfig The prompt configuration specifying which tools to enable
     */
    public ModularNovaS2SEventHandler(String phoneNumber, PromptConfiguration promptConfig) {
        this.toolRegistry = new ToolRegistry();
        initializeToolsFromConfig(phoneNumber, promptConfig);
    }

    /**
     * Creates a handler with a custom tool registry.
     * @param toolRegistry The pre-configured tool registry
     */
    public ModularNovaS2SEventHandler(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * Initializes all available tools using auto-discovery.
     * @param phoneNumber The caller's phone number
     */
    private void initializeAllTools(String phoneNumber) {
        log.info("Auto-discovering and initializing ALL tools for phone number: {}", phoneNumber);

        ToolFactory factory = new ToolFactory(phoneNumber);
        for (Tool tool : factory.createAllTools()) {
            toolRegistry.register(tool);
        }

        log.info("Registered {} auto-discovered tools", toolRegistry.getAllTools().size());
    }

    /**
     * Initializes only the tools specified in the PromptConfiguration using auto-discovery.
     * @param phoneNumber The caller's phone number
     * @param promptConfig The prompt configuration
     */
    private void initializeToolsFromConfig(String phoneNumber, PromptConfiguration promptConfig) {
        log.info("Auto-discovering and initializing tools from config for phone number: {}", phoneNumber);
        log.info("Tools to enable: {}", promptConfig.getToolNames());

        ToolFactory factory = new ToolFactory(phoneNumber);
        for (Tool tool : factory.createToolsByName(promptConfig.getToolNames())) {
            toolRegistry.register(tool);
        }

        log.info("Registered {} configured tools", toolRegistry.getAllTools().size());
    }

    /**
     * Gets the tool registry for adding/removing tools.
     * @return The tool registry
     */
    public ToolRegistry getToolRegistry() {
        return toolRegistry;
    }

    /**
     * Sets the hangup callback on the hangup tool.
     * @param hangupCallback The callback to invoke
     */
    public void setHangupCallback(Runnable hangupCallback) {
        Tool hangupTool = toolRegistry.getTool("hangupTool");
        if (hangupTool instanceof HangupTool) {
            ((HangupTool) hangupTool).setHangupCallback(hangupCallback);
        } else {
            log.warn("HangupTool not found in registry, cannot set callback");
        }
    }

    @Override
    protected void handleToolInvocation(String toolUseId, String toolName, String content, Map<String, Object> output) {
        if (toolName == null) {
            log.warn("Received null toolName");
            return;
        }

        log.info("Handling tool invocation: {} with content: {}", toolName, content);

        boolean handled = toolRegistry.handle(toolName, toolUseId, content, output);
        if (!handled) {
            output.put("status", "error");
            output.put("message", "Unknown tool: " + toolName);
        }
    }

    @Override
    public PromptStartEvent.ToolConfiguration getToolConfiguration() {
        return toolRegistry.getToolConfiguration();
    }

    @Override
    public void close() {
        if (pinpointClient != null) {
            pinpointClient.close();
        }
    }
}
