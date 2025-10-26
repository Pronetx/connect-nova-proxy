package com.example.s2s.voipgateway;

import org.mjsip.ua.MediaConfig;

public class NovaMediaConfig extends MediaConfig {
    private static final String DEFAULT_VOICE_ID = "en_us_matthew";
    private static final String DEFAULT_PROMPT = "You are a friendly assistant. The user and you will engage in a spoken dialog " +
            "exchanging the transcripts of a natural real-time conversation. Keep your responses short, " +
            "generally two or three sentences for chatty scenarios.";
    private static final int DEFAULT_MAX_TOKENS = 1024;
    private static final float DEFAULT_NOVA_TOP_P = 0.9F;
    private static final float DEFAULT_NOVA_TEMPERATURE = 0.7F;
    private String novaVoiceId = DEFAULT_VOICE_ID;
    private String novaPrompt = DEFAULT_PROMPT;
    private int novaMaxTokens = DEFAULT_MAX_TOKENS;
    private float novaTopP = DEFAULT_NOVA_TOP_P;
    private float novaTemperature = DEFAULT_NOVA_TEMPERATURE;

    public String getNovaVoiceId() {
        return novaVoiceId;
    }

    public void setNovaVoiceId(String novaVoiceId) {
        this.novaVoiceId = novaVoiceId;
    }

    public String getNovaPrompt() {
        return novaPrompt;
    }

    public void setNovaPrompt(String novaPrompt) {
        this.novaPrompt = novaPrompt;
    }

    public int getNovaMaxTokens() {
        return novaMaxTokens;
    }

    public void setNovaMaxTokens(int novaMaxTokens) {
        this.novaMaxTokens = novaMaxTokens;
    }

    public float getNovaTopP() {
        return novaTopP;
    }

    public void setNovaTopP(float novaTopP) {
        this.novaTopP = novaTopP;
    }

    public float getNovaTemperature() {
        return novaTemperature;
    }

    public void setNovaTemperature(float novaTemperature) {
        this.novaTemperature = novaTemperature;
    }
}
