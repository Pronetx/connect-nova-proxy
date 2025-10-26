package com.example.s2s.voipgateway.nova.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.pinpoint.PinpointClient;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Universal provider that discovers and instantiates all Tool implementations.
 * Uses reflection to automatically match constructor parameters to available dependencies.
 *
 * This single provider eliminates the need for separate provider classes per tool.
 */
public class ToolProvider {
    private static final Logger log = LoggerFactory.getLogger(ToolProvider.class);
    private static final String TOOLS_PACKAGE = "com.example.s2s.voipgateway.nova.tools";

    /**
     * List of all known tool implementation classes.
     * Add new tool class names here when creating new tools.
     */
    private static final List<String> TOOL_CLASSES = Arrays.asList(
            TOOLS_PACKAGE + ".DateTimeTool",
            TOOLS_PACKAGE + ".SendOTPTool",
            TOOLS_PACKAGE + ".VerifyOTPTool",
            TOOLS_PACKAGE + ".HangupTool",
            TOOLS_PACKAGE + ".GetCallerPhoneTool",
            TOOLS_PACKAGE + ".CollectAddressTool",
            TOOLS_PACKAGE + ".SendSMSTool"
    );

    /**
     * Creates all tool instances with automatic dependency injection.
     *
     * @param phoneNumber The phone number for SMS operations
     * @param pinpointClient AWS Pinpoint client for sending SMS
     * @param otpStore Shared OTP storage map
     * @return List of instantiated tools
     */
    public List<Tool> createAllTools(String phoneNumber, PinpointClient pinpointClient, Map<String, String> otpStore) {
        List<Tool> tools = new ArrayList<>();

        for (String className : TOOL_CLASSES) {
            try {
                Tool tool = createTool(className, phoneNumber, pinpointClient, otpStore);
                if (tool != null) {
                    tools.add(tool);
                    log.info("Created tool: {} ({})", tool.getName(), className);
                }
            } catch (Exception e) {
                log.error("Failed to create tool from class {}: {}", className, e.getMessage(), e);
            }
        }

        return tools;
    }

    /**
     * Creates a single tool instance with automatic dependency injection.
     */
    private Tool createTool(String className, String phoneNumber, PinpointClient pinpointClient, Map<String, String> otpStore) {
        try {
            Class<?> clazz = Class.forName(className);

            // Check if it implements Tool
            if (!Tool.class.isAssignableFrom(clazz) || clazz.isInterface()) {
                log.warn("Class {} does not implement Tool interface", className);
                return null;
            }

            // Try no-arg constructor first (preferred for simple tools like DateTimeTool)
            try {
                Constructor<?> noArgConstructor = clazz.getConstructor();
                return (Tool) noArgConstructor.newInstance();
            } catch (NoSuchMethodException e) {
                // No no-arg constructor, try dependency injection
            }

            // Try constructors with dependency injection in order of complexity
            Constructor<?>[] constructors = clazz.getConstructors();

            for (Constructor<?> constructor : constructors) {
                Class<?>[] paramTypes = constructor.getParameterTypes();

                // Skip no-arg (already tried)
                if (paramTypes.length == 0) {
                    continue;
                }

                Object[] params = new Object[paramTypes.length];

                // Match parameters to available dependencies
                boolean canConstruct = true;
                for (int i = 0; i < paramTypes.length; i++) {
                    if (paramTypes[i] == PinpointClient.class) {
                        params[i] = pinpointClient;
                    } else if (paramTypes[i] == Map.class) {
                        params[i] = otpStore;
                    } else if (paramTypes[i] == String.class) {
                        // Only use phoneNumber for String params if other dependencies are also present
                        // This avoids incorrectly injecting phoneNumber into tools that don't need it
                        if (paramTypes.length > 1) {
                            params[i] = phoneNumber;
                        } else {
                            canConstruct = false;
                            break;
                        }
                    } else {
                        canConstruct = false;
                        break;
                    }
                }

                if (canConstruct) {
                    return (Tool) constructor.newInstance(params);
                }
            }

            log.warn("No compatible constructor found for tool: {}", className);
        } catch (Exception e) {
            log.error("Failed to instantiate tool {}: {}", className, e.getMessage(), e);
        }

        return null;
    }
}
