package com.example.s2s.voipgateway.nova.event;

import com.fasterxml.jackson.annotation.JsonGetter;
import lombok.Builder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents a prompt start event.
 */
@Builder
public class PromptStartEvent implements NovaSonicEvent {
    private PromptStart promptStart = new PromptStart();

    public PromptStartEvent() {
    }

    public PromptStartEvent(PromptStart promptStart) {
        this.promptStart = promptStart;
    }

    @JsonGetter
    public PromptStart getPromptStart() {
        return promptStart;
    }

    public void setPromptStart(PromptStart promptStart) {
        this.promptStart = promptStart;
    }

    @Builder
    public static class PromptStart {
        private String promptName;
        private MediaConfiguration textOutputConfiguration = new MediaConfiguration();
        private AudioOutputConfiguration audioOutputConfiguration = new AudioOutputConfiguration();
        private MediaConfiguration toolUseOutputConfiguration = new MediaConfiguration();
        private ToolConfiguration toolConfiguration = new ToolConfiguration();

        public PromptStart() {
        }

        public PromptStart(String promptName, MediaConfiguration textOutputConfiguration, AudioOutputConfiguration audioOutputConfiguration, MediaConfiguration toolUseOutputConfiguration, ToolConfiguration toolConfiguration) {
            this.promptName = promptName;
            this.textOutputConfiguration = textOutputConfiguration;
            this.audioOutputConfiguration = audioOutputConfiguration;
            this.toolUseOutputConfiguration = toolUseOutputConfiguration;
            this.toolConfiguration = toolConfiguration;
        }

        @JsonGetter
        public String getPromptName() {
            return promptName;
        }

        public void setPromptName(String promptName) {
            this.promptName = promptName;
        }

        @JsonGetter
        public MediaConfiguration getTextOutputConfiguration() {
            return textOutputConfiguration;
        }

        public void setTextOutputConfiguration(MediaConfiguration textOutputConfiguration) {
            this.textOutputConfiguration = textOutputConfiguration;
        }

        @JsonGetter
        public AudioOutputConfiguration getAudioOutputConfiguration() {
            return audioOutputConfiguration;
        }

        public void setAudioOutputConfiguration(AudioOutputConfiguration audioOutputConfiguration) {
            this.audioOutputConfiguration = audioOutputConfiguration;
        }

        @JsonGetter
        public MediaConfiguration getToolUseOutputConfiguration() {
            return toolUseOutputConfiguration;
        }

        public void setToolUseOutputConfiguration(MediaConfiguration toolUseOutputConfiguration) {
            this.toolUseOutputConfiguration = toolUseOutputConfiguration;
        }

        @JsonGetter
        public ToolConfiguration getToolConfiguration() {
            return toolConfiguration;
        }

        public void setToolConfiguration(ToolConfiguration toolConfiguration) {
            this.toolConfiguration = toolConfiguration;
        }
    }

    @Builder
    public static class AudioOutputConfiguration {
        private String mediaType;
        private int sampleRateHertz;
        private short sampleSizeBits;
        private short channelCount;
        private String voiceId;
        private String encoding;
        private String audioType;

        public AudioOutputConfiguration() {
        }

        public AudioOutputConfiguration(String mediaType, int sampleRateHertz, short sampleSizeBits, short channelCount, String voiceId, String encoding, String audioType) {
            this.mediaType = mediaType;
            this.sampleRateHertz = sampleRateHertz;
            this.sampleSizeBits = sampleSizeBits;
            this.channelCount = channelCount;
            this.voiceId = voiceId;
            this.encoding = encoding;
            this.audioType = audioType;
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
        public String getVoiceId() {
            return voiceId;
        }

        public void setVoiceId(String voiceId) {
            this.voiceId = voiceId;
        }

        @JsonGetter
        public String getEncoding() {
            return encoding;
        }

        public void setEncoding(String encoding) {
            this.encoding = encoding;
        }

        @JsonGetter
        public String getAudioType() {
            return audioType;
        }

        public void setAudioType(String audioType) {
            this.audioType = audioType;
        }
    }
    @Builder
    public static class ToolConfiguration {
        private List<Tool> tools = new ArrayList<>();

        public ToolConfiguration() {
        }

        public ToolConfiguration(List<Tool> tools) {
            this.tools = tools;
        }

        @JsonGetter
        public List<Tool> getTools() {
            return tools;
        }

        public void setTools(List<Tool> tools) {
            this.tools = tools;
        }
    }
    @Builder
    public static class Tool {
        private ToolSpec toolSpec = new ToolSpec();

        public Tool() {
        }

        public Tool(ToolSpec toolSpec) {
            this.toolSpec = toolSpec;
        }

        @JsonGetter
        public ToolSpec getToolSpec() {
            return toolSpec;
        }

        public void setToolSpec(ToolSpec toolSpec) {
            this.toolSpec = toolSpec;
        }
    }
    @Builder
    public static class ToolSpec {
        private String name;
        private String description;
        private Map<String, String> inputSchema = new HashMap<>();

        public ToolSpec() {
        }

        public ToolSpec(String name, String description, Map<String, String> inputSchema) {
            this.name = name;
            this.description = description;
            this.inputSchema = inputSchema;
        }

        @JsonGetter
        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        @JsonGetter
        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        @JsonGetter
        public Map<String, String> getInputSchema() {
            return inputSchema;
        }

        public void setInputSchema(Map<String, String> inputSchema) {
            this.inputSchema = inputSchema;
        }
    }
    @Builder
    public static class ToolSchema {
        private String type;
        private Map<String, Object> properties = new HashMap<>();
        private List<Object> required = new ArrayList<>();

        public ToolSchema() {
        }

        public ToolSchema(String type, Map<String, Object> properties, List<Object> required) {
            this.type = type;
            this.properties = properties;
            this.required = required;
        }

        @JsonGetter
        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        @JsonGetter
        public Map<String, Object> getProperties() {
            return properties;
        }

        public void setProperties(Map<String, Object> properties) {
            this.properties = properties;
        }

        @JsonGetter
        public List<Object> getRequired() {
            return required;
        }

        public void setRequired(List<Object> required) {
            this.required = required;
        }
    }
}
