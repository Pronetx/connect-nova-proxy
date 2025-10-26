package com.example.s2s.voipgateway.nova.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * Tool for hanging up the phone call.
 */
public class HangupTool implements Tool {
    private static final Logger log = LoggerFactory.getLogger(HangupTool.class);
    private Runnable hangupCallback;

    public HangupTool() {
    }

    /**
     * Sets the callback to be invoked when hanging up.
     * @param hangupCallback The callback runnable
     */
    public void setHangupCallback(Runnable hangupCallback) {
        this.hangupCallback = hangupCallback;
    }

    @Override
    public String getName() {
        return "hangupTool";
    }

    @Override
    public String getDescription() {
        return "End the phone call when the conversation is complete or the caller requests to hang up";
    }

    @Override
    public Map<String, String> getInputSchema() {
        return ToolSpecs.DEFAULT_TOOL_SPEC;
    }

    @Override
    public void handle(String toolUseId, String content, Map<String, Object> output) throws Exception {
        log.info("Hangup tool invoked - will terminate call after goodbye");
        output.put("status", "acknowledged");
        output.put("message", "Goodbye");

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
}
