package com.example.s2s.voipgateway.nova.transcode;

/**
 * Implementation of ITU-T G.711 μ-law encoding for transcoding linear PCM to μ-law
 * - Input: 8000Hz, 16-bit samples, 1 channel (mono) linear PCM
 * - Output: 8000Hz, 8-bit samples, 1 channel (mono) μ-law
 *
 * This implementation follows the official ITU-T G.711 specification
 */
public class PcmToULawTranscoder {

    // u-law encoding table based on the G.711 standard
    private static final byte[] ULAW_COMPRESS_TABLE = new byte[] {
            0, 0, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 3, 3,
            4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4,
            5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5,
            5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5, 5,
            6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6,
            6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6,
            6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6,
            6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6,
            7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
            7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
            7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
            7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
            7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
            7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
            7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7,
            7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7
    };

    // BIAS value for u-law encoding as defined in the G.711 standard
    private static final int BIAS = 0x84;

    /**
     * Encode a 16-bit signed linear PCM sample to 8-bit μ-law
     * Algorithm follows the ITU-T G.711 specification
     *
     * @param pcmSample 16-bit signed linear PCM sample
     * @return 8-bit μ-law encoded sample
     */
    public static byte linearToULaw(short pcmSample) {
        // Get the sign and absolute value
        int sign = (pcmSample < 0) ? 0x80 : 0x00;

        if (pcmSample < 0) {
            pcmSample = (short)-pcmSample;
        }

        // Apply bias per G.711 spec
        // To avoid overflows
        if (pcmSample > 32635) {
            pcmSample = 32635;
        }

        // Add bias
        pcmSample = (short)(pcmSample + BIAS);

        // Find the segment
        int exponent = ULAW_COMPRESS_TABLE[(pcmSample >> 7) & 0xFF];
        int mantissa;

        // Quantize the sample within the segment
        if (exponent < 7) {
            mantissa = (pcmSample >> (exponent + 3)) & 0x0F;
        } else {
            mantissa = (pcmSample >> 10) & 0x0F;
        }

        // Assemble the μ-law byte
        int ulawByte = ~(sign | (exponent << 4) | mantissa) & 0xFF;

        return (byte)ulawByte;
    }

    /**
     * Transcode PCM data to μ-law (for in-memory processing)
     *
     * @param pcmData Raw 16-bit PCM data (little-endian)
     * @return Transcoded 8-bit μ-law data
     */
    public static byte[] transcodeBytes(byte[] pcmData) {
        // Ensure we have complete samples
        int validBytes = pcmData.length - (pcmData.length % 2);
        int sampleCount = validBytes / 2;
        byte[] ulawData = new byte[sampleCount];

        for (int i = 0; i < validBytes; i += 2) {
            // Convert byte pair to short - handle little-endian encoding
            short sample = (short)((pcmData[i] & 0xFF) | ((pcmData[i + 1] & 0xFF) << 8));
            ulawData[i/2] = linearToULaw(sample);
        }

        return ulawData;
    }
}