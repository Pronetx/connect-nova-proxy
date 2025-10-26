package com.example.s2s.voipgateway.nova.tools;

import com.example.s2s.voipgateway.nova.AbstractNovaS2SEventHandler;
import com.example.s2s.voipgateway.nova.event.PromptStartEvent;
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
}
