package com.example.s2s.voipgateway.nova;


import com.example.s2s.voipgateway.nova.event.*;
import com.example.s2s.voipgateway.nova.observer.InputEventsInteractObserver;
import com.example.s2s.voipgateway.nova.observer.InteractObserver;
import io.reactivex.rxjava3.processors.ReplayProcessor;
import io.reactivex.rxjava3.schedulers.Schedulers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeAsyncClient;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelWithBidirectionalStreamInput;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelWithBidirectionalStreamRequest;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Client for setting up Nova Sonic asynchronous streams.
 */
public class NovaS2SBedrockInteractClient {
    private static final Logger log = LoggerFactory.getLogger(NovaS2SBedrockInteractClient.class);
    private final BedrockRuntimeAsyncClient bedrockClient;
    private String modelId;

    public NovaS2SBedrockInteractClient(BedrockRuntimeAsyncClient bedrockClient, String modelId) {
        this.bedrockClient = bedrockClient;
        this.modelId = modelId;
    }

    /**
     * Opens a new bidirectional stream with Nova Sonic.
     * @param sessionStartEvent The SessionStartEvent
     * @param promptStartEvent The PromptStartEvent
     * @param systemPrompt The system prompt
     * @param eventHandler The event handler for the session.
     * @return An observer for outbound events.
     */
    public InteractObserver<NovaSonicEvent> interactMultimodal(
            SessionStartEvent sessionStartEvent,
            PromptStartEvent promptStartEvent,
            TextInputEvent systemPrompt,
            NovaS2SEventHandler eventHandler
    ) {
        InvokeModelWithBidirectionalStreamRequest request = InvokeModelWithBidirectionalStreamRequest.builder()
                .modelId(modelId)
                .build();

        // we expire the messages after one minute to save memory after connection as this is aligned with the timeout
        ReplayProcessor<InvokeModelWithBidirectionalStreamInput> publisher = ReplayProcessor.createWithTime(
                1, TimeUnit.MINUTES, Schedulers.io()
        );
        NovaS2SResponseHandler responseHandler = new NovaS2SResponseHandler(eventHandler);
        log.info("Invoking model with bidirectional stream ...");
        CompletableFuture<Void> completableFuture = bedrockClient.invokeModelWithBidirectionalStream(request, publisher, responseHandler);

        // if the request fails make sure to tell the publisher to close down properly
        completableFuture.exceptionally(throwable -> {
            log.error("Bedrock streaming error occurred", throwable);

            // Log specific error types for better troubleshooting
            String errorMsg = throwable.getMessage();
            if (errorMsg != null) {
                if (errorMsg.contains("ValidationException")) {
                    log.error("Nova Sonic validation error - likely session state mismatch");
                } else if (errorMsg.contains("ThrottlingException")) {
                    log.error("Bedrock throttling - too many requests");
                } else if (errorMsg.contains("ServiceUnavailableException")) {
                    log.error("Bedrock service temporarily unavailable");
                }
            }

            publisher.onError(throwable);
            return null;
        });

        // if the request finishes make sure to close the publisher properly
        completableFuture.thenApply(result -> {
            publisher.onComplete();
            return result;
        });

        InputEventsInteractObserver inputObserver = new InputEventsInteractObserver(publisher);

        // send the session start
        log.info("Sending session start event ...");
        inputObserver.onNext(sessionStartEvent);
        log.info("Sending prompt start event ...");
        inputObserver.onNext(promptStartEvent);

        log.info("Sending system prompt ...");
        inputObserver.onNext(ContentStartEvent.createTextContentStart(systemPrompt.getTextInput().getPromptName(),
                systemPrompt.getTextInput().getContentName()));
        inputObserver.onNext(systemPrompt);
        inputObserver.onNext(ContentEndEvent.create(systemPrompt.getTextInput().getPromptName(),
                systemPrompt.getTextInput().getContentName()));


        log.info("Input observer ready");
        return inputObserver;
    }
}
