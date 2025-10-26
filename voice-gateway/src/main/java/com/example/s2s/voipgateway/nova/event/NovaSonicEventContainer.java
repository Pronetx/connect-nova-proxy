package com.example.s2s.voipgateway.nova.event;

import com.fasterxml.jackson.annotation.JsonGetter;

/**
 * The base Amazon Nova Sonic asynchronous message.  Contains NovaSonicEvents.
 */
public class NovaSonicEventContainer {
    private NovaSonicEvent event;

    public NovaSonicEventContainer() { }
    public NovaSonicEventContainer(NovaSonicEvent event) {
        this.event = event;
    }

    @JsonGetter("event")
    public NovaSonicEvent getEvent() {
        return event;
    }

    public void setEvent(NovaSonicEvent event) {
        this.event = event;
    }
}
