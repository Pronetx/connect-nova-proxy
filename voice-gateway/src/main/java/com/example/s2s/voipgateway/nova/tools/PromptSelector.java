package com.example.s2s.voipgateway.nova.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Selects which prompt configuration to use based on call context.
 * Supports routing by caller phone number, called number (DID), or other criteria.
 * Loads routing rules from prompt-routing.properties in classpath.
 */
public class PromptSelector {
    private static final Logger log = LoggerFactory.getLogger(PromptSelector.class);

    private static final String DEFAULT_PROMPT = "prompts/default.prompt";
    private static final Map<String, String> CALLER_ROUTING = new HashMap<>();
    private static final Map<String, String> DID_ROUTING = new HashMap<>();
    private static String configuredDefault = DEFAULT_PROMPT;

    static {
        loadRoutingConfiguration();
    }

    /**
     * Loads routing configuration from prompt-routing.properties file.
     */
    private static void loadRoutingConfiguration() {
        try (InputStream input = PromptSelector.class.getClassLoader()
                .getResourceAsStream("prompt-routing.properties")) {

            if (input == null) {
                log.info("No prompt-routing.properties found, using default routing");
                return;
            }

            Properties props = new Properties();
            props.load(input);

            // Load default
            configuredDefault = props.getProperty("default", DEFAULT_PROMPT);

            // Load caller-based routing
            for (String key : props.stringPropertyNames()) {
                if (key.startsWith("caller.")) {
                    String phoneNumber = key.substring("caller.".length());
                    String promptPath = props.getProperty(key);
                    CALLER_ROUTING.put(phoneNumber, promptPath);
                    log.info("Loaded caller routing: {} -> {}", phoneNumber, promptPath);
                }
            }

            // Load DID-based routing
            for (String key : props.stringPropertyNames()) {
                if (key.startsWith("did.")) {
                    String phoneNumber = key.substring("did.".length());
                    String promptPath = props.getProperty(key);
                    DID_ROUTING.put(phoneNumber, promptPath);
                    log.info("Loaded DID routing: {} -> {}", phoneNumber, promptPath);
                }
            }

            log.info("Loaded prompt routing configuration: {} caller rules, {} DID rules, default={}",
                    CALLER_ROUTING.size(), DID_ROUTING.size(), configuredDefault);

        } catch (IOException e) {
            log.error("Failed to load prompt-routing.properties", e);
        }
    }

    /**
     * Selects a prompt based on caller information.
     * @param callerPhone The caller's phone number
     * @param calledNumber The number that was called (DID)
     * @return Path to the prompt file to use
     */
    public static String selectPrompt(String callerPhone, String calledNumber) {
        // Priority 1: Check if caller has specific routing
        if (callerPhone != null && CALLER_ROUTING.containsKey(callerPhone)) {
            String prompt = CALLER_ROUTING.get(callerPhone);
            log.info("Selected prompt '{}' based on caller phone: {}", prompt, callerPhone);
            return prompt;
        }

        // Priority 2: Check if called number (DID) has specific routing
        if (calledNumber != null && DID_ROUTING.containsKey(calledNumber)) {
            String prompt = DID_ROUTING.get(calledNumber);
            log.info("Selected prompt '{}' based on called number: {}", prompt, calledNumber);
            return prompt;
        }

        // Priority 3: Check environment variable
        String envPrompt = System.getenv("NOVA_PROMPT_FILE");
        if (envPrompt != null && !envPrompt.isEmpty()) {
            log.info("Selected prompt '{}' from NOVA_PROMPT_FILE env var", envPrompt);
            return envPrompt;
        }

        // Priority 4: Configured default prompt
        log.info("Using default prompt: {}", configuredDefault);
        return configuredDefault;
    }

    /**
     * Adds a caller-specific prompt routing.
     * @param callerPhone The caller's phone number
     * @param promptPath Path to the prompt file
     */
    public static void addCallerRouting(String callerPhone, String promptPath) {
        CALLER_ROUTING.put(callerPhone, promptPath);
        log.info("Added caller routing: {} -> {}", callerPhone, promptPath);
    }

    /**
     * Adds a DID-specific prompt routing.
     * @param calledNumber The number that was called
     * @param promptPath Path to the prompt file
     */
    public static void addDIDRouting(String calledNumber, String promptPath) {
        DID_ROUTING.put(calledNumber, promptPath);
        log.info("Added DID routing: {} -> {}", calledNumber, promptPath);
    }

    /**
     * Clears all routing rules.
     */
    public static void clearRouting() {
        CALLER_ROUTING.clear();
        DID_ROUTING.clear();
        log.info("Cleared all prompt routing rules");
    }

    /**
     * Gets current caller routing map (for inspection/debugging).
     * @return Unmodifiable view of caller routing
     */
    public static Map<String, String> getCallerRouting() {
        return new HashMap<>(CALLER_ROUTING);
    }

    /**
     * Gets current DID routing map (for inspection/debugging).
     * @return Unmodifiable view of DID routing
     */
    public static Map<String, String> getDIDRouting() {
        return new HashMap<>(DID_ROUTING);
    }
}
