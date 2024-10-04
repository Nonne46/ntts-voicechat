package com.ntts;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

public class nttsClient {

    private static final String TTS_URL = "https://ntts.fdev.team/api/v1/tts";
    private static final String SPEAKERS_URL = "https://ntts.fdev.team/api/v1/tts/speakers";
    private final String token;

    public nttsClient(String token) {
        this.token = token;
    }

    public static class Voice {
        private String description;
        private String gender;
        private String name;
        private String source;
        private List<String> speakers;
        @SerializedName("voice_type")
        private String voiceType;

        public String getDescription() {
            return description;
        }

        public String getGender() {
            return gender;
        }

        public String getName() {
            return name;
        }

        public String getSource() {
            return source;
        }

        public List<String> getSpeakers() {
            return speakers;
        }

        public String getVoiceType() {
            return voiceType;
        }
    }

    public static class VoiceResponse {
        private List<Voice> voices;

        public List<Voice> getVoices() {
            return voices;
        }
    }

    private String sanitize(String text) {
        String replacedText = text.replace("🥚", " яйцо ");
        return replacedText.replaceAll("[^\\p{L}\\p{P}\\p{Zs}\\p{N}]", "");
    }

    public byte[] generateTTS(String text, String speaker) {
        String cleanedText = sanitize(text);
        if (cleanedText.isEmpty()) {
            cleanedText = " ";
        }

        try {
            URL url = new URL(TTS_URL + "?speaker=" + speaker + "&text=" + cleanedText + "&ext=wav&use_48k=1");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Authorization", "Bearer " + token);

            try {
                if (connection.getResponseCode() >= 200 && connection.getResponseCode() < 300) {
                    return connection.getInputStream().readAllBytes();
                }
            } finally {
                connection.disconnect();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public List<Voice> getSpeakers() {
        try {
            URL url = new URL(SPEAKERS_URL);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");

            try {
                if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();

                    Gson gson = new Gson();
                    VoiceResponse voiceResponse = gson.fromJson(response.toString(), VoiceResponse.class);
                    return voiceResponse.getVoices();
                }
            } finally {
                connection.disconnect();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return List.of();
    }
}
