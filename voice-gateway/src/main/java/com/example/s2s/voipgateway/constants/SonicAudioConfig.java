package com.example.s2s.voipgateway.constants;

/**
 * Constants for Amazon Nova Sonic audio configuration.
 * This should typically be the same for VoIP applications unless high definition audio support is required.
 */
public interface SonicAudioConfig {
    short SAMPLE_SIZE = (short) 16;
    int SAMPLE_RATE = 8000;
    short CHANNEL_COUNT = (short) 1;
    String ENCODING_BASE64 = "base64";
}
