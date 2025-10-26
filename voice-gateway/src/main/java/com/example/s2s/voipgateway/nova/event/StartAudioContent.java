package com.example.s2s.voipgateway.nova.event;

import com.fasterxml.jackson.annotation.JsonGetter;
import lombok.Builder;

/**
 * Represents a start audio event.
 */
@Builder
public class StartAudioContent implements NovaSonicEvent {
    public static final String TYPE_AUDIO="AUDIO";
    private ContentStart contentStart=new ContentStart();

    public StartAudioContent() {
    }

    public StartAudioContent(ContentStart contentStart) {
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
        private String type=TYPE_AUDIO;
        private boolean interactive=true;
        private AudioInputConfiguration audioInputConfiguration=new AudioInputConfiguration();

        public ContentStart() {
        }

        public ContentStart(String promptName, String contentName, String type, boolean interactive, AudioInputConfiguration audioInputConfiguration) {
            this.promptName = promptName;
            this.contentName = contentName;
            this.type = type;
            this.interactive = interactive;
            this.audioInputConfiguration = audioInputConfiguration;
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
        public AudioInputConfiguration getAudioInputConfiguration() {
            return audioInputConfiguration;
        }

        public void setAudioInputConfiguration(AudioInputConfiguration audioInputConfiguration) {
            this.audioInputConfiguration = audioInputConfiguration;
        }
    }
    @Builder
    public static class AudioInputConfiguration {
        private String mediaType;
        private int sampleRateHertz;
        private short sampleSizeBits;
        private short channelCount;
        private String audioType;
        private String encoding;

        public AudioInputConfiguration() {
        }

        public AudioInputConfiguration(String mediaType, int sampleRateHertz, short sampleSizeBits, short channelCount, String audioType, String encoding) {
            this.mediaType = mediaType;
            this.sampleRateHertz = sampleRateHertz;
            this.sampleSizeBits = sampleSizeBits;
            this.channelCount = channelCount;
            this.audioType = audioType;
            this.encoding = encoding;
        }

        @JsonGetter
        public String getMediaType() {
            return mediaType;
        }

        public void setMediaType(String mediaType) {
            this.mediaType = mediaType;
        }

        @JsonGetter
        public int getSampleRateHertz() {
            return sampleRateHertz;
        }

        public void setSampleRateHertz(int sampleRateHertz) {
            this.sampleRateHertz = sampleRateHertz;
        }

        @JsonGetter
        public short getSampleSizeBits() {
            return sampleSizeBits;
        }

        public void setSampleSizeBits(short sampleSizeBits) {
            this.sampleSizeBits = sampleSizeBits;
        }

        @JsonGetter
        public short getChannelCount() {
            return channelCount;
        }

        public void setChannelCount(short channelCount) {
            this.channelCount = channelCount;
        }

        @JsonGetter
        public String getAudioType() {
            return audioType;
        }

        public void setAudioType(String audioType) {
            this.audioType = audioType;
        }

        @JsonGetter
        public String getEncoding() {
            return encoding;
        }

        public void setEncoding(String encoding) {
            this.encoding = encoding;
        }
    }
}
