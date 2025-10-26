package com.example.s2s.voipgateway.nova.event;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonGetter;
import lombok.Builder;
import lombok.Singular;

import java.util.HashMap;
import java.util.Map;

/**
 * Contains configuration maps with a mediaType.
 */
@Builder
public class MediaConfiguration {
    private String mediaType;
    @Singular
    private Map<String,String> properties=new HashMap<>();

    public MediaConfiguration(String mediaType, Map<String, String> properties) {
        this.mediaType = mediaType;
        this.properties = properties;
    }

    public MediaConfiguration() {
    }

    public MediaConfiguration(String mediaType) {
        this.mediaType = mediaType;
    }

    @JsonGetter
    public String getMediaType() {
        return mediaType;
    }

    public void setMediaType(String mediaType) {
        this.mediaType = mediaType;
    }

    @JsonAnyGetter
    public Map<String, String> getProperties() {
        return properties;
    }

    public void setProperties(Map<String, String> properties) {
        this.properties = properties;
    }
}
