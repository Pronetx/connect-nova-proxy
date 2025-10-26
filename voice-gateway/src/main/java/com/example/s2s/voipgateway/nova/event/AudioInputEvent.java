package com.example.s2s.voipgateway.nova.event;

import com.fasterxml.jackson.annotation.JsonGetter;
import lombok.Builder;

/**
 * Represents an audio input event.
 */
@Builder
public class AudioInputEvent implements NovaSonicEvent {
    private AudioInput audioInput = new AudioInput();

    public AudioInputEvent() {
    }

    public AudioInputEvent(AudioInput audioInput) {
        this.audioInput = audioInput;
    }

    @JsonGetter
    public AudioInput getAudioInput() {
        return audioInput;
    }

    public void setAudioInput(AudioInput audioInput) {
        this.audioInput = audioInput;
    }

    @Builder
    public static class AudioInput {
        private String promptName;
        private String contentName;
        private String content;
        private String role;

        public AudioInput() {
        }

        public AudioInput(String promptName, String contentName, String content, String role) {
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
