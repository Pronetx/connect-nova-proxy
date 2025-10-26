package com.example.s2s.voipgateway.nova.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Tool for providing current date and time information.
 * Example of a simple tool with no parameters.
 */
public class DateTimeTool implements Tool {
    private static final Logger log = LoggerFactory.getLogger(DateTimeTool.class);
    private final String timezone;

    public DateTimeTool() {
        this(System.getenv().getOrDefault("TZ", "America/Los_Angeles"));
    }

    public DateTimeTool(String timezone) {
        this.timezone = timezone;
    }

    @Override
    public String getName() {
        return "getDateTimeTool";
    }

    @Override
    public String getDescription() {
        return "Get the current date and time. Use this when the caller asks what time it is or what day it is.";
    }

    @Override
    public Map<String, String> getInputSchema() {
        return ToolSpecs.DEFAULT_TOOL_SPEC;
    }

    @Override
    public void handle(String toolUseId, String content, Map<String, Object> output) throws Exception {
        try {
            ZonedDateTime now = ZonedDateTime.now(ZoneId.of(timezone));

            String formattedDate = now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy"));
            String formattedTime = now.format(DateTimeFormatter.ofPattern("h:mm a"));

            output.put("status", "success");
            output.put("date", formattedDate);
            output.put("time", formattedTime);
            output.put("timezone", timezone);
            output.put("message", String.format("Today is %s and the current time is %s %s",
                    formattedDate, formattedTime, timezone));

            log.info("DateTime tool invoked - returned: {} {}", formattedDate, formattedTime);
        } catch (Exception e) {
            log.error("Error in DateTime tool", e);
            output.put("status", "error");
            output.put("message", "Unable to get date and time information");
        }
    }
}
