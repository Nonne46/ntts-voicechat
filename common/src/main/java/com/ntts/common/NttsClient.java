package com.ntts.common;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import org.slf4j.Logger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class NttsClient {
    private static final String TTS_URL = "https://ntts.fdev.team/api/v1/tts";
    private static final String SPEAKERS_URL = "https://ntts.fdev.team/api/v1/tts/speakers";
    private static final String EFFECTS_URL = "https://ntts.fdev.team/api/v1/tts/effects";
    private static final String TOKEN_INFO_URL = "https://ntts.fdev.team/api/v1/user/token/";
    private static final String USER_AGENT = "NTTS-Minecraft-Mod/1.0";
    private static final int MAX_ATTEMPTS = 3;
    private static final long DEFAULT_RATE_LIMIT_MILLIS = 30_000L;
    private static final long UNAVAILABLE_BACKOFF_MILLIS = 5_000L;

    private final String token;
    private final Logger logger;

    private final Object pacingLock = new Object();

    private volatile ProviderState state;
    private volatile TokenLimits tokenLimits = TokenLimits.unknown();
    private volatile long locallyUsedCharacters;
    private volatile long blockedUntil;
    private long nextRequestAt;

    public NttsClient(String token, Logger logger) {
        this.token = token == null ? "" : token.trim();
        this.logger = logger;
        state = isConfiguredToken(this.token) ? ProviderState.READY : ProviderState.UNCONFIGURED;
    }

    public SynthesisResult generateTts(String normalizedText, String speaker, String effect) {
        if (!canRequest()) {
            return SynthesisResult.failure(state);
        }
        if (normalizedText == null || normalizedText.isBlank()) {
            return SynthesisResult.failure(state);
        }

        int characterCount = normalizedText.codePointCount(0, normalizedText.length());
        TokenLimits currentLimits = tokenLimits;
        if (!currentLimits.permits(characterCount, locallyUsedCharacters)) {
            state = ProviderState.QUOTA_EXHAUSTED;
            return SynthesisResult.failure(state);
        }
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (!paceRequest(currentLimits)) {
                state = ProviderState.UNAVAILABLE;
                return SynthesisResult.failure(state);
            }
            HttpURLConnection connection = null;
            try {
                String query = "speaker=" + encode(speaker)
                        + "&text=" + encode(normalizedText)
                        + "&ext=wav&use_48k=1";
                if (effect != null && !effect.isBlank()) {
                    query += "&effect=" + encode(effect);
                }
                connection = (HttpURLConnection) URI.create(TTS_URL + "?" + query).toURL().openConnection();
                connection.setRequestMethod("GET");
                connection.setRequestProperty("User-Agent", USER_AGENT);
                connection.setRequestProperty("Authorization", "Bearer " + token);
                connection.setConnectTimeout(10_000);
                connection.setReadTimeout(60_000);

                int status = connection.getResponseCode();
                if (status >= 200 && status < 300) {
                    byte[] data = connection.getInputStream().readAllBytes();
                    locallyUsedCharacters += characterCount;
                    state = currentLimits.isQuotaExhausted(locallyUsedCharacters)
                            ? ProviderState.QUOTA_EXHAUSTED
                            : ProviderState.READY;
                    blockedUntil = 0L;
                    return SynthesisResult.success(data);
                }
                if (status == HttpURLConnection.HTTP_UNAUTHORIZED
                        || status == HttpURLConnection.HTTP_FORBIDDEN) {
                    state = ProviderState.INVALID;
                    blockedUntil = Long.MAX_VALUE;
                    logger.warn("/N/TTS rejected the configured credential with HTTP {}", status);
                    return SynthesisResult.failure(state);
                }
                if (status == 429) {
                    state = ProviderState.RATE_LIMITED;
                    blockedUntil = System.currentTimeMillis() + retryAfterMillis(connection);
                    logger.debug("/N/TTS rate limit reached; dropping audio during the Retry-After interval");
                    return SynthesisResult.failure(state);
                }
                if (status >= 500 && attempt < MAX_ATTEMPTS) {
                    sleepBeforeRetry(attempt);
                    continue;
                }
                state = ProviderState.UNAVAILABLE;
                blockedUntil = System.currentTimeMillis() + UNAVAILABLE_BACKOFF_MILLIS;
                logger.warn("/N/TTS request failed with HTTP {}", status);
                return SynthesisResult.failure(state);
            } catch (Exception exception) {
                if (attempt < MAX_ATTEMPTS) {
                    sleepBeforeRetry(attempt);
                    continue;
                }
                state = ProviderState.UNAVAILABLE;
                blockedUntil = System.currentTimeMillis() + UNAVAILABLE_BACKOFF_MILLIS;
                logger.warn("/N/TTS is temporarily unavailable: {}", exception.toString());
                return SynthesisResult.failure(state);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }

        state = ProviderState.UNAVAILABLE;
        blockedUntil = System.currentTimeMillis() + UNAVAILABLE_BACKOFF_MILLIS;
        return SynthesisResult.failure(state);
    }

    public boolean refreshTokenInfo() {
        if (state == ProviderState.UNCONFIGURED || state == ProviderState.INVALID) {
            return false;
        }

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(TOKEN_INFO_URL + encode(token)).toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(30_000);

            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_UNAUTHORIZED
                    || status == HttpURLConnection.HTTP_FORBIDDEN
                    || status == HttpURLConnection.HTTP_NOT_FOUND) {
                state = ProviderState.INVALID;
                blockedUntil = Long.MAX_VALUE;
                logger.warn("/N/TTS token metadata endpoint rejected the configured credential");
                return false;
            }
            if (status != HttpURLConnection.HTTP_OK) {
                logger.warn("Could not load /N/TTS token metadata (HTTP {})", status);
                return false;
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                TokenInfoResponse response = new Gson().fromJson(reader, TokenInfoResponse.class);
                if (response == null || response.data == null || !"success".equals(response.status)) {
                    state = ProviderState.INVALID;
                    blockedUntil = Long.MAX_VALUE;
                    logger.warn("/N/TTS token metadata response did not accept the configured credential");
                    return false;
                }
                TokenLimits loadedLimits = TokenLimits.from(response.data);
                tokenLimits = loadedLimits;
                locallyUsedCharacters = Math.max(0L, response.data.charsUsed);
                if (loadedLimits.isExpired()) {
                    state = ProviderState.INVALID;
                    blockedUntil = Long.MAX_VALUE;
                    logger.warn("The configured /N/TTS credential has expired");
                    return false;
                }
                state = loadedLimits.isQuotaExhausted(locallyUsedCharacters)
                        ? ProviderState.QUOTA_EXHAUSTED
                        : ProviderState.READY;
                blockedUntil = 0L;
                return true;
            }
        } catch (Exception exception) {
            logger.warn("Failed to load /N/TTS token metadata: {}", exception.toString());
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    public List<Voice> getVoices() {
        if (state == ProviderState.UNCONFIGURED || state == ProviderState.INVALID) {
            return Collections.emptyList();
        }

        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(SPEAKERS_URL).toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(30_000);

            if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    VoiceResponse response = new Gson().fromJson(reader, VoiceResponse.class);
                    return response == null || response.voices == null
                            ? Collections.emptyList()
                            : response.voices;
                }
            }
        } catch (Exception exception) {
            logger.warn("Failed to load /N/TTS speakers: {}", exception.toString());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return Collections.emptyList();
    }

    public List<String> getEffects() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(EFFECTS_URL).toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(30_000);

            if (connection.getResponseCode() == HttpURLConnection.HTTP_OK) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    EffectResponse response = new Gson().fromJson(reader, EffectResponse.class);
                    return response == null || response.effects == null
                            ? Collections.emptyList()
                            : response.effects;
                }
            }
        } catch (Exception exception) {
            logger.warn("Failed to load /N/TTS effects: {}", exception.toString());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return Collections.emptyList();
    }

    public ProviderState getState() {
        refreshTemporaryState();
        return state;
    }

    public boolean canRequest() {
        refreshTemporaryState();
        return state == ProviderState.READY;
    }

    public int getRequestCharacterLimit(int configuredLimit) {
        int providerLimit = tokenLimits.charactersPerRequest;
        return providerLimit > 0 ? Math.min(configuredLimit, providerLimit) : configuredLimit;
    }

    public String getSafeLimitSummary() {
        return tokenLimits.safeSummary(locallyUsedCharacters);
    }

    public static String normalizeText(String text, int maximumCodePoints) {
        if (text == null || maximumCodePoints <= 0) {
            return "";
        }
        String normalized = text.replace("🥚", " яйцо ")
                .replaceAll("[^\\p{L}\\p{P}\\p{Zs}\\p{N}]", "")
                .replaceAll("\\s+", " ")
                .trim();
        int codePoints = normalized.codePointCount(0, normalized.length());
        if (codePoints <= maximumCodePoints) {
            return normalized;
        }
        return normalized.substring(0, normalized.offsetByCodePoints(0, maximumCodePoints)).trim();
    }

    private boolean paceRequest(TokenLimits limits) {
        long intervalMillis = limits.requestIntervalMillis();
        if (intervalMillis <= 0L) {
            return true;
        }
        synchronized (pacingLock) {
            long now = System.currentTimeMillis();
            long waitMillis = Math.max(0L, nextRequestAt - now);
            if (waitMillis > 0L) {
                try {
                    Thread.sleep(waitMillis);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            nextRequestAt = System.currentTimeMillis() + intervalMillis;
            return true;
        }
    }

    private void refreshTemporaryState() {
        TokenLimits limits = tokenLimits;
        if (limits.isExpired()) {
            state = ProviderState.INVALID;
            blockedUntil = Long.MAX_VALUE;
            return;
        }
        if (limits.isQuotaExhausted(locallyUsedCharacters)) {
            state = ProviderState.QUOTA_EXHAUSTED;
            return;
        }
        ProviderState current = state;
        if ((current == ProviderState.RATE_LIMITED || current == ProviderState.UNAVAILABLE)
                && System.currentTimeMillis() >= blockedUntil) {
            state = ProviderState.READY;
            blockedUntil = 0L;
        }
    }

    private static boolean isConfiguredToken(String value) {
        return !value.isBlank() && !"your_token_here".equalsIgnoreCase(value);
    }

    private static long retryAfterMillis(HttpURLConnection connection) {
        String retryAfter = connection.getHeaderField("Retry-After");
        if (retryAfter == null) {
            return DEFAULT_RATE_LIMIT_MILLIS;
        }
        try {
            long seconds = Long.parseLong(retryAfter.trim());
            return Math.max(1_000L, Math.min(seconds * 1_000L, 300_000L));
        } catch (NumberFormatException ignored) {
            return DEFAULT_RATE_LIMIT_MILLIS;
        }
    }

    private static void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(250L << (attempt - 1));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    public enum ProviderState {
        UNCONFIGURED,
        READY,
        INVALID,
        RATE_LIMITED,
        QUOTA_EXHAUSTED,
        UNAVAILABLE;

        public String displayName() {
            return name().toLowerCase(Locale.ROOT).replace('_', '-');
        }
    }

    public static final class SynthesisResult {
        private final byte[] audio;
        private final ProviderState state;

        private SynthesisResult(byte[] audio, ProviderState state) {
            this.audio = audio;
            this.state = state;
        }

        public static SynthesisResult success(byte[] audio) {
            return new SynthesisResult(audio, ProviderState.READY);
        }

        public static SynthesisResult failure(ProviderState state) {
            return new SynthesisResult(null, state);
        }

        public byte[] getAudio() {
            return audio;
        }

        public ProviderState getState() {
            return state;
        }

        public boolean isSuccess() {
            return audio != null && audio.length > 0;
        }
    }

    private static final class TokenLimits {
        private final int type;
        private final int charactersPerRequest;
        private final double requestsPerSecond;
        private final double delayPerRequest;
        private final long characterQuota;
        private final Instant expiresAt;
        private final String expiresDisplay;

        private TokenLimits(
                int type,
                int charactersPerRequest,
                double requestsPerSecond,
                double delayPerRequest,
                long characterQuota,
                Instant expiresAt,
                String expiresDisplay
        ) {
            this.type = type;
            this.charactersPerRequest = charactersPerRequest;
            this.requestsPerSecond = requestsPerSecond;
            this.delayPerRequest = delayPerRequest;
            this.characterQuota = characterQuota;
            this.expiresAt = expiresAt;
            this.expiresDisplay = expiresDisplay;
        }

        private static TokenLimits unknown() {
            return new TokenLimits(0, 0, 0D, 0D, 0L, null, "unknown");
        }

        private static TokenLimits from(TokenInfo data) {
            Instant expiration = null;
            if (data.expires != null && !data.expires.isBlank()) {
                try {
                    expiration = Instant.parse(data.expires);
                } catch (DateTimeParseException ignored) {
                    // Keep the raw value for diagnostics without rejecting otherwise valid metadata.
                }
            }
            return new TokenLimits(
                    data.type,
                    Math.max(0, data.charsPerRequest),
                    Math.max(0D, data.requestsPerSecond),
                    Math.max(0D, data.delayPerRequest),
                    Math.max(0L, data.charQuota),
                    expiration,
                    data.expires == null || data.expires.isBlank() ? "unknown" : data.expires
            );
        }

        private boolean permits(int characterCount, long usedCharacters) {
            return type != 3 || characterQuota <= 0L || usedCharacters + characterCount <= characterQuota;
        }

        private boolean isQuotaExhausted(long usedCharacters) {
            return type == 3 && characterQuota > 0L && usedCharacters >= characterQuota;
        }

        private boolean isExpired() {
            return expiresAt != null && !Instant.now().isBefore(expiresAt);
        }

        private long requestIntervalMillis() {
            if (type == 1 && requestsPerSecond > 0D) {
                return Math.max(1L, (long) Math.ceil(1_000D / requestsPerSecond));
            }
            if (type == 2 && delayPerRequest > 0D) {
                return Math.max(1L, (long) Math.ceil(delayPerRequest * 1_000D));
            }
            return 0L;
        }

        private String safeSummary(long usedCharacters) {
            String maximum = charactersPerRequest > 0
                    ? charactersPerRequest + " characters per message"
                    : "message limit unknown";
            String expiration = "unknown".equals(expiresDisplay)
                    ? ""
                    : ", expires " + expiresDisplay;
            if (type == 1) {
                return maximum + ", " + decimal(requestsPerSecond)
                        + (requestsPerSecond == 1D ? " request/second" : " requests/second")
                        + expiration;
            }
            if (type == 2) {
                return maximum + ", " + decimal(delayPerRequest) + " seconds between requests" + expiration;
            }
            if (type == 3) {
                long remaining = Math.max(0L, characterQuota - usedCharacters);
                return maximum + ", " + remaining + " of " + characterQuota
                        + " quota characters remaining" + expiration;
            }
            return "Not available yet";
        }

        private static String decimal(double value) {
            if (value == Math.rint(value)) {
                return Long.toString((long) value);
            }
            return String.format(Locale.ROOT, "%.2f", value);
        }
    }

    private static final class TokenInfoResponse {
        private TokenInfo data;
        private String status;
    }

    private static final class TokenInfo {
        private int type;
        @SerializedName("chars_per_request")
        private int charsPerRequest;
        @SerializedName("requests_per_second")
        private double requestsPerSecond;
        @SerializedName("delay_per_request")
        private double delayPerRequest;
        @SerializedName("char_quota")
        private long charQuota;
        @SerializedName("chars_used")
        private long charsUsed;
        private String expires;
    }

    public static final class Voice {
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
            return speakers == null ? Collections.emptyList() : speakers;
        }

        public String getVoiceType() {
            return voiceType;
        }
    }

    private static final class VoiceResponse {
        private List<Voice> voices;
    }

    private static final class EffectResponse {
        private List<String> effects;
    }
}
