package com.example.s2s.voipgateway.nova.event;

import com.fasterxml.jackson.annotation.JsonGetter;

/**
 * Represents a session start event.
 */
public class SessionStartEvent implements NovaSonicEvent {
    private SessionStart sessionStart=new SessionStart();

    @JsonGetter
    public SessionStart getSessionStart() {
        return sessionStart;
    }

    public void setSessionStart(SessionStart sessionStart) {
        this.sessionStart = sessionStart;
    }

    public SessionStartEvent(int maxTokens, float topP, float temperature) {
        sessionStart.inferenceConfiguration.setMaxTokens(maxTokens);
        sessionStart.inferenceConfiguration.setTopP(topP);
        sessionStart.inferenceConfiguration.setTemperature(temperature);
    }
    public static class SessionStart {
        private InferenceConfiguration inferenceConfiguration = new InferenceConfiguration();

        public SessionStart() {
        }

        @JsonGetter("inferenceConfiguration")
        public InferenceConfiguration getInferenceConfiguration() {
            return inferenceConfiguration;
        }

        public void setInferenceConfiguration(InferenceConfiguration inferenceConfiguration) {
            this.inferenceConfiguration = inferenceConfiguration;
        }
    }

    public static class InferenceConfiguration {
        private int maxTokens;
        private float topP;
        private float temperature;

        @JsonGetter("maxTokens")
        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }

        @JsonGetter("topP")
        public float getTopP() {
            return topP;
        }

        public void setTopP(float topP) {
            this.topP = topP;
        }

        @JsonGetter("temperature")
        public float getTemperature() {
            return temperature;
        }

        public void setTemperature(float temperature) {
            this.temperature = temperature;
        }
    }
}
