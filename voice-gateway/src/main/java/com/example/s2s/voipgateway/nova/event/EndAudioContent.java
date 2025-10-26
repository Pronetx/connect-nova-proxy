package com.example.s2s.voipgateway.nova.event;

import com.fasterxml.jackson.annotation.JsonGetter;
import lombok.Builder;

/**
 * Represents an end audio content event.
 */
@Builder
public class EndAudioContent implements NovaSonicEvent {
    private ContentEnd contentEnd = new ContentEnd();

    public EndAudioContent() {
    }

    public EndAudioContent(ContentEnd contentEnd) {
        this.contentEnd = contentEnd;
    }

    @JsonGetter
    public ContentEnd getContentEnd() {
        return contentEnd;
    }

    public void setContentEnd(ContentEnd contentEnd) {
        this.contentEnd = contentEnd;
    }

    @Builder
    public static class ContentEnd {
        private String promptName;
        private String contentName;

        public ContentEnd() { }
        public ContentEnd(String promptName, String contentName) {
            this.promptName = promptName;
            this.contentName = contentName;
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
    }
}
