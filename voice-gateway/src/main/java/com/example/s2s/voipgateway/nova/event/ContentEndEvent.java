package com.example.s2s.voipgateway.nova.event;

import com.fasterxml.jackson.annotation.JsonGetter;
import lombok.Builder;

/**
 * Represents a content end event.
 */
@Builder
public class ContentEndEvent implements NovaSonicEvent {
    private ContentEnd contentEnd = new ContentEnd();

    public ContentEndEvent() { }
    public ContentEndEvent(ContentEnd contentEnd) {
        this.contentEnd = contentEnd;
    }

    @JsonGetter
    public ContentEnd getContentEnd() {
        return contentEnd;
    }

    public void setContentEnd(ContentEnd contentEnd) {
        this.contentEnd = contentEnd;
    }

    public static class ContentEnd {
        private String promptName;
        private String contentName;

        public ContentEnd() {
        }

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
    public static ContentEndEvent create(String promptName, String contentName) {
        return new ContentEndEvent(new ContentEnd(promptName, contentName));
    }
}
