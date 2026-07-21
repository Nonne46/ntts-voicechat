package com.ntts.common;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

final class PcmAudio {
    private PcmAudio() {
    }

    static short[] fromWav(byte[] audioData) throws Exception {
        try (ByteArrayInputStream bytes = new ByteArrayInputStream(audioData);
             AudioInputStream input = AudioSystem.getAudioInputStream(bytes)) {
            AudioFormat source = input.getFormat();
            AudioFormat pcm = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    source.getSampleRate(),
                    16,
                    source.getChannels(),
                    source.getChannels() * 2,
                    source.getSampleRate(),
                    false
            );

            try (AudioInputStream converted = AudioSystem.getAudioInputStream(pcm, input)) {
                byte[] pcmBytes = readAll(converted);
                short[] samples = new short[pcmBytes.length / 2];
                for (int i = 0; i < samples.length; i++) {
                    int low = pcmBytes[i * 2] & 0xFF;
                    int high = pcmBytes[i * 2 + 1] << 8;
                    samples[i] = (short) (high | low);
                }
                return samples;
            }
        }
    }

    private static byte[] readAll(AudioInputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }
}
