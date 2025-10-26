package com.example.s2s.voipgateway.nova.transcode;

/**
 * A utility for converting µ-law (G.711) encoded audio to Linear PCM.
 *
 * Input: 8000Hz, 8-bit samples, 1 byte per frame
 * Output: 8000Hz, 16-bit samples, 2 bytes per frame
 */
public class UlawToPcmTranscoder {
    // µ-law to linear conversion table
    private static final short[] ULAW_TO_LINEAR_TABLE = new short[256];

    // Initialize the conversion table
    static {
        // Fill the µ-law to linear table with conversion values
        for (int i = 0; i < 256; i++) {
            ULAW_TO_LINEAR_TABLE[i] = ulawToLinear((byte) i);
        }
    }

    /**
     * Converts a µ-law encoded byte to a 16-bit linear PCM sample.
     *
     * @param ulawByte The µ-law encoded byte
     * @return The 16-bit linear PCM sample
     */
    private static short ulawToLinear(byte ulawByte) {
        // Flip the bits (µ-law data is usually stored with bit inversion)
        int ulaw = ~ulawByte & 0xFF;

        // Extract sign, exponent, and mantissa
        int sign = (ulaw & 0x80) >> 7;
        int exponent = (ulaw & 0x70) >> 4;
        int mantissa = ulaw & 0x0F;

        // Calculate the magnitude using the formula for µ-law decoding
        int magnitude = ((mantissa << 1) + 33) << exponent;
        magnitude = magnitude - 33;

        // Apply sign and return the 16-bit sample
        return (short) (sign == 1 ? -magnitude : magnitude);
    }
    /**
     * Converts µ-law byte array to linear PCM.
     *
     * @param ulawData The µ-law encoded byte array
     * @return The linear PCM data as a byte array (twice the length of input)
     */
    public static byte[] convertByteArray(byte[] ulawData) {
        byte[] pcmData = new byte[ulawData.length * 2];

        for (int i = 0; i < ulawData.length; i++) {
            short linearSample = ULAW_TO_LINEAR_TABLE[ulawData[i] & 0xFF];

            // Little-endian conversion (low byte first)
            pcmData[i * 2] = (byte) (linearSample & 0xFF);
            pcmData[i * 2 + 1] = (byte) ((linearSample >> 8) & 0xFF);
        }

        return pcmData;
    }
}