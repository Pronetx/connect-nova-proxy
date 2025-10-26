package com.example.s2s.voipgateway.nova.event;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonGetter;
import lombok.Builder;
import lombok.Singular;

import java.util.HashMap;
import java.util.Map;

/**
 * Represents a content start event.
 */
@Builder
public class ContentStartEvent implements NovaSonicEvent {
    private ContentStart contentStart=new ContentStart();

    public ContentStartEvent() {
    }

    public ContentStartEvent(ContentStart contentStart) {
        this.contentStart = contentStart;
    }

    @JsonGetter
    public ContentStart getContentStart() {
        return contentStart;
    }

    public void setContentStart(ContentStart contentStart) {
        this.contentStart = contentStart;
    }

    @Builder
    public static class ContentStart {
        private String promptName;
        private String contentName;
        private String type;
        private boolean interactive;
        private MediaConfiguration textInputConfiguration=new MediaConfiguration();
        @Singular
        private Map<String, Object> properties =new HashMap<>();

        public ContentStart() {
        }

        public ContentStart(String promptName, String contentName, String type, boolean interactive, MediaConfiguration textInputConfiguration, Map<String, Object> toolResultInputConfiguration) {
            this.promptName = promptName;
            this.contentName = contentName;
            this.type = type;
            this.interactive = interactive;
            this.textInputConfiguration = textInputConfiguration;
            this.properties = toolResultInputConfiguration;
        }

        @JsonGetter
        public String getPromptName() {
            return promptName;
        }

        public void setPromptName(String promptName) {
            this.promptName = promptName;
        }

        @JsonGetter
        public String getContentName() {
            return contentName;
        }

        public void setContentName(String contentName) {
            this.contentName = contentName;
        }

        @JsonGetter
        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        @JsonGetter
        public boolean isInteractive() {
            return interactive;
        }

        public void setInteractive(boolean interactive) {
            this.interactive = interactive;
        }

        @JsonGetter
        public MediaConfiguration getTextInputConfiguration() {
            return textInputConfiguration;
        }

        public void setTextInputConfiguration(MediaConfiguration textInputConfiguration) {
            this.textInputConfiguration = textInputConfiguration;
        }

        @JsonAnyGetter
        public Map<String, Object> getProperties() {
            return properties;
        }

        public void setProperties(Map<String, Object> properties) {
            this.properties = properties;
        }
    }
    public static ContentStartEvent createTextContentStart(String promptName, String contentName) {
        ContentStartEvent ret = new ContentStartEvent();
        ret.contentStart.setPromptName(promptName);
        ret.contentStart.setContentName(contentName);
        ret.contentStart.setType("TEXT");
        ret.contentStart.setInteractive(true);
        ret.contentStart.textInputConfiguration.setMediaType("text/plain");
        return ret;
    }
}
