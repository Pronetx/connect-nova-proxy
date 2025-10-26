package com.example.s2s.voipgateway.nova.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.pinpoint.PinpointClient;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

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
     * Discovers all Tool implementations in the tools package.
     * @return List of discovered tool class names
     */
    public List<String> discoverToolClasses() {
        List<String> toolClasses = new ArrayList<>();

        try {
            // Get all classes in the tools package
            String packagePath = TOOLS_PACKAGE.replace('.', '/');
            ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
            InputStream stream = classLoader.getResourceAsStream(packagePath);

            if (stream == null) {
                log.warn("Could not find tools package: {}", packagePath);
                return toolClasses;
            }

            BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
            Set<String> classNames = reader.lines()
                    .filter(line -> line.endsWith(".class"))
                    .map(line -> line.substring(0, line.lastIndexOf('.')))
                    .collect(Collectors.toSet());

            for (String className : classNames) {
                String fullClassName = TOOLS_PACKAGE + "." + className;

                try {
                    Class<?> clazz = Class.forName(fullClassName);

                    // Check if it implements Tool and is not an interface
                    if (Tool.class.isAssignableFrom(clazz) && !clazz.isInterface() && clazz != Tool.class) {
                        toolClasses.add(fullClassName);
                        log.debug("Discovered tool class: {}", fullClassName);
                    }
                } catch (ClassNotFoundException | NoClassDefFoundError e) {
                    log.debug("Could not load class {}: {}", fullClassName, e.getMessage());
                }
            }

            reader.close();
        } catch (Exception e) {
            log.error("Error discovering tool classes", e);
        }

        log.info("Discovered {} tool classes", toolClasses.size());
        return toolClasses;
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
     * Creates all discovered tools.
     * @return List of all tool instances
     */
    public List<Tool> createAllTools() {
        List<Tool> tools = new ArrayList<>();
        List<String> toolClasses = discoverToolClasses();

        for (String className : toolClasses) {
            Tool tool = createTool(className);
            if (tool != null) {
                tools.add(tool);
            }
        }

        log.info("Created {} tools", tools.size());
        return tools;
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
