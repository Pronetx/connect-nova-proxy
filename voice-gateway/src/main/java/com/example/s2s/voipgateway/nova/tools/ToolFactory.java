package com.example.s2s.voipgateway.nova.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.pinpoint.PinpointClient;

import java.lang.reflect.Constructor;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Factory for auto-discovering and creating tool instances.
 * Scans the classpath for Tool implementations and creates them with proper dependencies.
 */
public class ToolFactory {
    private static final Logger log = LoggerFactory.getLogger(ToolFactory.class);
    private static final String TOOLS_PACKAGE = "com.example.s2s.voipgateway.nova.tools";

    private final String phoneNumber;
    private final PinpointClient pinpointClient;
    private final Map<String, String> otpStore;

    public ToolFactory(String phoneNumber) {
        this.phoneNumber = phoneNumber;
        this.otpStore = new ConcurrentHashMap<>();

        // Initialize Pinpoint client
        String region = System.getenv().getOrDefault("AWS_REGION", "us-west-2");
        this.pinpointClient = PinpointClient.builder()
                .region(Region.of(region))
                .build();

        log.info("ToolFactory initialized for phone number: {} in region: {}", phoneNumber, region);
    }

    /**
     * Discovers all Tool implementations using ToolProvider.
     * The ToolProvider maintains a list of known tools and instantiates them with dependency injection.
     * @return List of discovered Tool instances
     */
    public List<Tool> discoverTools() {
        ToolProvider provider = new ToolProvider();
        List<Tool> tools = provider.createAllTools(phoneNumber, pinpointClient, otpStore);
        log.info("Discovered {} tools via ToolProvider", tools.size());
        return tools;
    }

    /**
     * Creates a tool instance from a class name.
     * Automatically injects dependencies based on constructor parameters.
     * @param className The fully qualified class name
     * @return Tool instance, or null if creation failed
     */
    public Tool createTool(String className) {
        try {
            Class<?> clazz = Class.forName(className);

            // Try constructors in order of complexity
            Constructor<?>[] constructors = clazz.getConstructors();

            for (Constructor<?> constructor : constructors) {
                Class<?>[] paramTypes = constructor.getParameterTypes();
                Object[] params = new Object[paramTypes.length];

                // Match parameters to available dependencies
                boolean canConstruct = true;
                for (int i = 0; i < paramTypes.length; i++) {
                    if (paramTypes[i] == String.class) {
                        params[i] = phoneNumber;
                    } else if (paramTypes[i] == PinpointClient.class) {
                        params[i] = pinpointClient;
                    } else if (paramTypes[i] == Map.class) {
                        params[i] = otpStore;
                    } else {
                        canConstruct = false;
                        break;
                    }
                }

                if (canConstruct) {
                    Tool tool = (Tool) constructor.newInstance(params);
                    log.info("Created tool: {} ({})", tool.getName(), className);
                    return tool;
                }
            }

            log.warn("No compatible constructor found for tool: {}", className);
        } catch (Exception e) {
            log.error("Failed to create tool from class {}: {}", className, e.getMessage(), e);
        }

        return null;
    }

    /**
     * Creates all discovered tools using ToolProvider.
     * @return List of all tool instances
     */
    public List<Tool> createAllTools() {
        return discoverTools();
    }

    /**
     * Creates tools specified by name.
     * @param toolNames List of tool names to create
     * @return List of created tool instances
     */
    public List<Tool> createToolsByName(List<String> toolNames) {
        List<Tool> allTools = createAllTools();
        Map<String, Tool> toolMap = new HashMap<>();

        for (Tool tool : allTools) {
            toolMap.put(tool.getName(), tool);
        }

        List<Tool> selectedTools = new ArrayList<>();
        for (String toolName : toolNames) {
            Tool tool = toolMap.get(toolName);
            if (tool != null) {
                selectedTools.add(tool);
                log.info("Selected tool: {}", toolName);
            } else {
                log.warn("Tool not found: {}", toolName);
            }
        }

        return selectedTools;
    }
}
