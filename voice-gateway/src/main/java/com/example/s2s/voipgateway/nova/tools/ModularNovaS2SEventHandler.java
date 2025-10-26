package com.example.s2s.voipgateway.nova.tools;

import com.example.s2s.voipgateway.nova.AbstractNovaS2SEventHandler;
import com.example.s2s.voipgateway.nova.event.PromptStartEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.pinpoint.PinpointClient;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Modular S2S Event Handler that uses a tool registry.
 * Makes it easy to add new tools without modifying this class.
 * Tools can be configured via PromptConfiguration files.
 */
public class ModularNovaS2SEventHandler extends AbstractNovaS2SEventHandler {
    private static final Logger log = LoggerFactory.getLogger(ModularNovaS2SEventHandler.class);
    private final ToolRegistry toolRegistry;
    private PinpointClient pinpointClient;

    /**
     * Creates a handler with default tools (all tools enabled).
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
     * Initializes all available tools.
     * @param phoneNumber The caller's phone number
     */
    private void initializeAllTools(String phoneNumber) {
        initializeDependencies();

        log.info("Initializing ALL tools for phone number: {}", phoneNumber);

        // Shared OTP store for send and verify tools
        Map<String, String> otpStore = new ConcurrentHashMap<>();

        // Register all available tools
        toolRegistry.register(new SendOTPTool(pinpointClient, phoneNumber, otpStore));
        toolRegistry.register(new VerifyOTPTool(otpStore));
        toolRegistry.register(new DateTimeTool());
        toolRegistry.register(new HangupTool());
    }

    /**
     * Initializes only the tools specified in the PromptConfiguration.
     * @param phoneNumber The caller's phone number
     * @param promptConfig The prompt configuration
     */
    private void initializeToolsFromConfig(String phoneNumber, PromptConfiguration promptConfig) {
        initializeDependencies();

        log.info("Initializing tools from config for phone number: {}", phoneNumber);
        log.info("Tools to register: {}", promptConfig.getToolNames());

        // Shared OTP store for send and verify tools
        Map<String, String> otpStore = new ConcurrentHashMap<>();

        // Register only the tools specified in the config
        for (String toolName : promptConfig.getToolNames()) {
            Tool tool = createTool(toolName, phoneNumber, otpStore);
            if (tool != null) {
                toolRegistry.register(tool);
                log.info("Registered tool: {}", toolName);
            } else {
                log.warn("Unknown tool in config: {}", toolName);
            }
        }
    }

    /**
     * Initializes common dependencies like Pinpoint client.
     */
    private void initializeDependencies() {
        String region = System.getenv().getOrDefault("AWS_REGION", "us-west-2");
        this.pinpointClient = PinpointClient.builder()
                .region(Region.of(region))
                .build();
        log.info("Initialized dependencies in region: {}", region);
    }

    /**
     * Factory method to create tools by name.
     * @param toolName The tool name
     * @param phoneNumber The caller's phone number
     * @param otpStore Shared OTP store
     * @return The created tool, or null if unknown
     */
    private Tool createTool(String toolName, String phoneNumber, Map<String, String> otpStore) {
        switch (toolName) {
            case "sendOTPTool":
                return new SendOTPTool(pinpointClient, phoneNumber, otpStore);
            case "verifyOTPTool":
                return new VerifyOTPTool(otpStore);
            case "getDateTimeTool":
                return new DateTimeTool();
            case "hangupTool":
                return new HangupTool();
            default:
                return null;
        }
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
