package com.example.s2s.voipgateway.nova;

import static software.amazon.awssdk.thirdparty.io.netty.util.internal.ObjectUtil.checkNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.async.SdkPublisher;
import software.amazon.awssdk.services.bedrockruntime.model.BidirectionalOutputPayloadPart;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelWithBidirectionalStreamOutput;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelWithBidirectionalStreamResponse;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelWithBidirectionalStreamResponseHandler;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

/**
 * Asynchronous response handler for Amazon Nova Sonic sessions.
 */
public class NovaS2SResponseHandler implements InvokeModelWithBidirectionalStreamResponseHandler {
    private static final Logger log = LoggerFactory.getLogger(NovaS2SResponseHandler.class);
    public static final String TYPE_TOOL = "TOOL";
    private final NovaS2SEventHandler handler;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private String toolUseId;
    private String toolUseContent;
    private String toolName;
    private boolean debugResponses = false;

    public NovaS2SResponseHandler(NovaS2SEventHandler handler) {
        this.handler = checkNotNull(handler, "handler cannot be null");
        debugResponses = System.getenv().getOrDefault("DEBUG_RESPONSES","false").equalsIgnoreCase("true");
    }

    @Override
    public void responseReceived(InvokeModelWithBidirectionalStreamResponse response) {
        log.info("Response received with Bedrock Nova S2S request id: {}", response.responseMetadata().requestId());
    }

    @Override
    public void onEventStream(SdkPublisher<InvokeModelWithBidirectionalStreamOutput> sdkPublisher) {
        log.info("Bedrock Nova S2S event stream received");
        CompletableFuture<Void> completableFuture = sdkPublisher.subscribe((output) -> output.accept(new Visitor() {
            @Override
            public void visitChunk(BidirectionalOutputPayloadPart event) {
                String payloadString =
                        StandardCharsets.UTF_8.decode((event.bytes().asByteBuffer().rewind().duplicate())).toString();
                if (debugResponses) {
                    log.debug("Received chunk: {}", payloadString);
                }
                handleJsonResponse(payloadString);
            }
        }));

        // if any of the chunks fail to parse or be handled ensure to send an error or they will get lost
        completableFuture.exceptionally(t -> {
            log.error("Event stream error", t);
            handler.onError(new Exception(t));
            return null;
        });

        handler.onStart();
    }

    @Override
    public void exceptionOccurred(Throwable t) {
        log.error("Event stream error, exception occurred", t);
        handler.onError(new Exception(t));
    }

    @Override
    public void complete() {
        log.info("Event stream complete");
        handler.onComplete();
    }

    /**
     * Handles a JSON response from the event stream.
     * @param msg The JSON string to be handled
     */
    private void handleJsonResponse(String msg) {
        try {
            JsonNode rootNode = objectMapper.readTree(msg);
            JsonNode eventNode = rootNode.get("event");

            if (eventNode != null) {
                if (eventNode.has("completionStart")) {
                    handler.handleCompletionStart(eventNode.get("completionStart"));
                } else if (eventNode.has("contentStart")) {
                    handler.handleContentStart(eventNode.get("contentStart"));
                } else if (eventNode.has("textOutput")) {
                    handler.handleTextOutput(eventNode.get("textOutput"));
                } else if (eventNode.has("audioOutput")) {
                    handler.handleAudioOutput(eventNode.get("audioOutput"));
                } else if (eventNode.has("toolUse")) {
                    toolUseId = eventNode.get("toolUse").get("toolUseId").asText();
                    toolName = eventNode.get("toolUse").get("toolName").asText();
                    toolUseContent = eventNode.get("toolUse").get("content").asText();
                } else if (eventNode.has("contentEnd")) {
                    if (TYPE_TOOL.equals(eventNode.get("contentEnd").get("type").asText())) {
                        handler.handleToolUse(eventNode, toolUseId, toolName, toolUseContent);
                    }
                } else if (eventNode.has("contentEnd")) {
                    handler.handleContentEnd(eventNode.get("contentEnd"));
                } else if (eventNode.has("completionEnd")) {
                    handler.handleCompletionEnd(eventNode.get("completionEnd"));
                } else {
                    log.info("Unhandled event: {}", eventNode);
                }
            }
        } catch (Exception e) {
            log.error("Error processing message", e);
            handler.onError(e);
        }
    }
}