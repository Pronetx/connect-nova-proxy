package com.example.s2s.voipgateway.nova.observer;


import com.example.s2s.voipgateway.nova.event.AudioInputEvent;
import com.example.s2s.voipgateway.nova.event.NovaSonicEvent;
import com.example.s2s.voipgateway.nova.event.NovaSonicEventContainer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.reactivestreams.Subscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.model.BidirectionalInputPayloadPart;
import software.amazon.awssdk.services.bedrockruntime.model.InvokeModelWithBidirectionalStreamInput;

import static software.amazon.awssdk.thirdparty.io.netty.util.internal.ObjectUtil.checkNotNull;

/**
 * InteractObserver for input events going to Amazon Nova Sonic.  Based on the Nova Sonic Java SDK sample code.
 */
public class InputEventsInteractObserver implements InteractObserver<NovaSonicEvent> {
    private static final Logger log = LoggerFactory.getLogger(InputEventsInteractObserver.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String SESSION_END = "{\"event\": {\"sessionEnd\": {}}}";
    private final Subscriber<InvokeModelWithBidirectionalStreamInput> subscriber;
    private boolean active = true;

    public InputEventsInteractObserver(Subscriber<InvokeModelWithBidirectionalStreamInput> subscriber) {
        this.subscriber = checkNotNull(subscriber, "subscriber cannot be null");
    }

    @Override
    public void onNext(NovaSonicEvent event) {
        String msg = writeJson(new NovaSonicEventContainer(event));
        if (!(event instanceof AudioInputEvent)) {
            log.info("publishing message {}", msg);
        }
        this.subscriber.onNext(inputBuilder(msg));
    }

    @Override
    public void onComplete() {
        try {
            log.info("onComplete()");
            this.subscriber.onNext(inputBuilder(SESSION_END));
            this.subscriber.onComplete();
        } finally {
            active=false;
        }
    }

    @Override
    public void onError(Exception error) {
        log.error("Error on input events interact observer", error);
       throw new RuntimeException(error);
    }

    public boolean isActive() {
        return active;
    }

    private BidirectionalInputPayloadPart inputBuilder (String input) {
        return InvokeModelWithBidirectionalStreamInput.chunkBuilder()
                .bytes(SdkBytes.fromUtf8String(input))
                .build();
    }

    /**
     * Converts a value to a JSON string.
     * @param value The value
     * @return The JSON string
     */
    private static String writeJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
