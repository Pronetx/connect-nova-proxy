package com.example.s2s.voipgateway.nova.tools;

import com.example.s2s.voipgateway.nova.AbstractNovaS2SEventHandler;
import com.example.s2s.voipgateway.nova.event.PromptStartEvent;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Map;

/**
 * S2S Event Handler that is aware of the date and time via tools.
 */
public class DateTimeNovaS2SEventHandler extends AbstractNovaS2SEventHandler {
    private static final Logger log = LoggerFactory.getLogger(DateTimeNovaS2SEventHandler.class);
    private static final String TIMEZONE = System.getenv().getOrDefault("TZ", "America/Los_Angeles");
    private Runnable hangupCallback;

    @Override
    protected void handleToolInvocation(String toolUseId, String toolName, String content, Map<String, Object> output) {
        if (toolName == null) {
            log.warn("Received null toolName");
        } else {
            switch (toolName) {
                case "getDateTool": {
                    handleGetDateTool(output);
                    break;

                }
                case "getTimeTool": {
                    handleGetTimeTool(output);
                    break;
                }
                case "hangupTool": {
                    handleHangupTool(output);
                    break;
                }
                default: {
                    log.warn("Unhandled tool: {}", toolName);
                }
            }
        }
    }

    @Override
    public PromptStartEvent.ToolConfiguration getToolConfiguration() {
        return PromptStartEvent.ToolConfiguration.builder()
                .tools(Arrays.asList(
                        PromptStartEvent.Tool.builder()
                                .toolSpec(PromptStartEvent.ToolSpec.builder()
                                        .name("getDateTool")
                                        .description("get information about the current date")
                                        .inputSchema(ToolSpecs.DEFAULT_TOOL_SPEC)
                                        .build()).build(),
                        PromptStartEvent.Tool.builder()
                                .toolSpec(
                                        PromptStartEvent.ToolSpec.builder()
                                                .name("getTimeTool")
                                                .description("get information about the current time")
                                                .inputSchema(ToolSpecs.DEFAULT_TOOL_SPEC)
                                                .build()).build(),
                        PromptStartEvent.Tool.builder()
                                .toolSpec(
                                        PromptStartEvent.ToolSpec.builder()
                                                .name("hangupTool")
                                                .description("End the phone call when the conversation is complete or the caller requests to hang up")
                                                .inputSchema(ToolSpecs.DEFAULT_TOOL_SPEC)
                                                .build()).build()
                ))
                .build();
    }

    /**
     * Handles a request to get the time.
     * @param contentNode The content node to write the response to.
     */
    private static void handleGetTimeTool(Map<String, Object> contentNode) {
        ZonedDateTime localTime = ZonedDateTime.now(ZoneId.of(TIMEZONE));
        contentNode.put("timezone", TIMEZONE);
        contentNode.put("formattedTime", localTime.format(DateTimeFormatter.ofPattern("HH:mm")));
    }

    /**
     * Handles a request to get the date.
     * @param contentNode The content node to write the response to.
     */
    private static void handleGetDateTool(Map<String, Object> contentNode) {
        LocalDate currentDate = LocalDate.now(ZoneId.of(TIMEZONE));
        contentNode.put("date", currentDate.format(DateTimeFormatter.ISO_DATE));
        contentNode.put("year", currentDate.getYear());
        contentNode.put("month", currentDate.getMonthValue());
        contentNode.put("day", currentDate.getDayOfMonth());
        contentNode.put("dayOfWeek", currentDate.getDayOfWeek().toString());
        contentNode.put("timezone", TIMEZONE);
    }

    /**
     * Handles a request to hang up the call.
     * @param contentNode The content node to write the response to.
     */
    private void handleHangupTool(Map<String, Object> contentNode) {
        log.info("Hangup tool invoked - will terminate call after goodbye");
        contentNode.put("status", "acknowledged");
        contentNode.put("message", "Goodbye");

        // Trigger hangup callback after giving Nova time to say goodbye
        if (hangupCallback != null) {
            new Thread(() -> {
                try {
                    Thread.sleep(3000); // Give Nova 3 seconds to say goodbye
                    log.info("Executing hangup callback to end call");
                    hangupCallback.run();
                } catch (Exception e) {
                    log.error("Failed to execute hangup callback", e);
                }
            }).start();
        } else {
            log.warn("No hangup callback configured - cannot terminate call");
        }
    }

    public void setHangupCallback(Runnable hangupCallback) {
        this.hangupCallback = hangupCallback;
    }
}
