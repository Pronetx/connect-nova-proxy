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
 */
public class ModularNovaS2SEventHandler extends AbstractNovaS2SEventHandler {
    private static final Logger log = LoggerFactory.getLogger(ModularNovaS2SEventHandler.class);
    private final ToolRegistry toolRegistry;
    private PinpointClient pinpointClient;

    /**
     * Creates a handler with default OTP tools.
     * @param phoneNumber The caller's phone number for SMS
     */
    public ModularNovaS2SEventHandler(String phoneNumber) {
        this.toolRegistry = new ToolRegistry();
        initializeDefaultTools(phoneNumber);
    }

    /**
     * Creates a handler with a custom tool registry.
     * @param toolRegistry The pre-configured tool registry
     */
    public ModularNovaS2SEventHandler(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * Initializes the default OTP and hangup tools.
     * @param phoneNumber The caller's phone number
     */
    private void initializeDefaultTools(String phoneNumber) {
        // Initialize Pinpoint client
        String region = System.getenv().getOrDefault("AWS_REGION", "us-west-2");
        this.pinpointClient = PinpointClient.builder()
                .region(Region.of(region))
                .build();

        log.info("Initializing tools for phone number: {} in region: {}", phoneNumber, region);

        // Shared OTP store for send and verify tools
        Map<String, String> otpStore = new ConcurrentHashMap<>();

        // Register OTP tools
        toolRegistry.register(new SendOTPTool(pinpointClient, phoneNumber, otpStore));
        toolRegistry.register(new VerifyOTPTool(otpStore));

        // Register hangup tool
        toolRegistry.register(new HangupTool());
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
