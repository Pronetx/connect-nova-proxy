package com.example.s2s.voipgateway.nova.event;

import com.fasterxml.jackson.annotation.JsonGetter;
import lombok.Builder;

/**
 * Represents a text input event.
 */
@Builder
public class TextInputEvent implements NovaSonicEvent {
    private TextInput textInput = new TextInput();
    public TextInputEvent() {}
    public TextInputEvent(TextInput textInput) {
        this.textInput = textInput;
    }

    @JsonGetter
    public TextInput getTextInput() {
        return textInput;
    }

    public void setTextInput(TextInput textInput) {
        this.textInput = textInput;
    }

    @Builder
    public static class TextInput {
        private String promptName;
        private String contentName;
        private String content;
        private String role;
        public TextInput() { }

        public TextInput(String promptName, String contentName, String content, String role) {
            this.promptName = promptName;
            this.contentName = contentName;
            this.content = content;
            this.role = role;
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
        public String getContent() {
            return content;
        }

        public void setContent(String content) {
            this.content = content;
        }

        @JsonGetter
        public String getRole() {
            return role;
        }

        public void setRole(String role) {
            this.role = role;
        }
    }
}
