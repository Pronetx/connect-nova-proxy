package com.example.s2s.voipgateway.nova.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses prompt configuration files with @tool directives.
 *
 * Format:
 * - Lines starting with @tool specify which tools to enable
 * - All other lines are part of the system prompt
 *
 * Example:
 * <pre>
 * You are a helpful customer support agent.
 * You can authenticate users and help them with issues.
 *
 * @tool sendOTPTool
 * @tool verifyOTPTool
 * @tool hangupTool
 * </pre>
 */
public class PromptConfiguration {
    private static final Logger log = LoggerFactory.getLogger(PromptConfiguration.class);
    private static final String TOOL_DIRECTIVE = "@tool";

    private final String systemPrompt;
    private final List<String> toolNames;

    public PromptConfiguration(String systemPrompt, List<String> toolNames) {
        this.systemPrompt = systemPrompt;
        this.toolNames = toolNames;
    }

    /**
     * Gets the system prompt (all non-@tool lines).
     * @return The system prompt
     */
    public String getSystemPrompt() {
        return systemPrompt;
    }

    /**
     * Gets the list of tool names to enable.
     * @return List of tool names
     */
    public List<String> getToolNames() {
        return toolNames;
    }

    /**
     * Parses a prompt configuration file from the filesystem.
     * @param filePath Path to the prompt file
     * @return Parsed configuration
     * @throws IOException if file cannot be read
     */
    public static PromptConfiguration fromFile(String filePath) throws IOException {
        log.info("Loading prompt configuration from file: {}", filePath);
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            return parse(reader);
        }
    }

    /**
     * Parses a prompt configuration file from classpath resources.
     * @param resourcePath Path to resource (e.g., "prompts/customer-support.prompt")
     * @return Parsed configuration
     * @throws IOException if resource cannot be read
     */
    public static PromptConfiguration fromResource(String resourcePath) throws IOException {
        log.info("Loading prompt configuration from resource: {}", resourcePath);
        InputStream inputStream = PromptConfiguration.class.getClassLoader().getResourceAsStream(resourcePath);
        if (inputStream == null) {
            throw new IOException("Resource not found: " + resourcePath);
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            return parse(reader);
        }
    }

    /**
     * Parses a prompt configuration from a string.
     * @param content The prompt file content
     * @return Parsed configuration
     */
    public static PromptConfiguration fromString(String content) {
        StringBuilder promptBuilder = new StringBuilder();
        List<String> toolNames = new ArrayList<>();

        String[] lines = content.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = line.trim();

            if (trimmed.startsWith(TOOL_DIRECTIVE)) {
                // Extract tool name after @tool
                String toolName = trimmed.substring(TOOL_DIRECTIVE.length()).trim();
                if (!toolName.isEmpty()) {
                    toolNames.add(toolName);
                    log.debug("Found tool directive: {}", toolName);
                }
            } else {
                // Part of system prompt
                promptBuilder.append(line).append("\n");
            }
        }

        String systemPrompt = promptBuilder.toString().trim();
        log.info("Parsed prompt configuration: {} tools, {} characters",
                toolNames.size(), systemPrompt.length());

        return new PromptConfiguration(systemPrompt, toolNames);
    }

    /**
     * Parses configuration from a BufferedReader.
     * @param reader The reader
     * @return Parsed configuration
     * @throws IOException if reading fails
     */
    private static PromptConfiguration parse(BufferedReader reader) throws IOException {
        StringBuilder contentBuilder = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            contentBuilder.append(line).append("\n");
        }
        return fromString(contentBuilder.toString());
    }

    @Override
    public String toString() {
        return String.format("PromptConfiguration{tools=%s, prompt=%d chars}",
                toolNames, systemPrompt.length());
    }
}
