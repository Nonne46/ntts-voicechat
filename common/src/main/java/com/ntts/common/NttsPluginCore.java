package com.ntts.common;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import de.maxhenkel.voicechat.api.ServerPlayer;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.VolumeCategory;
import de.maxhenkel.voicechat.api.audiochannel.AudioChannel;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoderMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public abstract class NttsPluginCore implements VoicechatPlugin {
    public static final String MOD_ID = "ntts";

    private static final String CATEGORY_ID = "ntts_cat";
    private static final String DEFAULT_SPEAKER = "narrator_d3";
    private static final String TOKEN_ENVIRONMENT_VARIABLE = "NTTS_TOKEN";
    private static final int DEFAULT_MAXIMUM_TEXT_LENGTH = 300;
    private static final int MAXIMUM_CONFIGURED_TEXT_LENGTH = 2_000;
    private static final int QUEUE_CAPACITY = 32;
    private static final int NO_FUZZY_MATCH = Integer.MAX_VALUE;
    private static final int SEARCH_TERM_PENALTY = 5;
    private static final List<String> CONFIGURATION_KEYS = List.of(
            "enabled",
            "mode",
            "voice_mode",
            "default_speaker",
            "effect",
            "allow_player_effects",
            "max_text_length"
    );
    private static final long AUDIO_CACHE_MAXIMUM_BYTES = 16L * 1024L * 1024L;
    private static final Path CONFIG_FILE = Path.of("config", "ntts.properties");
    private static final Path SPEAKER_DATA_FILE = Path.of("config", "ntts_speaker_data.json");
    private static final Path EFFECT_DATA_FILE = Path.of("config", "ntts_effect_data.json");
    private static final Path PLAYER_ADMIN_DATA_FILE = Path.of("config", "ntts_player_admin.json");

    protected final Logger logger = LoggerFactory.getLogger("ntts-plugin");

    private final Map<UUID, String> speakerData = new HashMap<>();
    private final Map<UUID, String> effectData = new HashMap<>();
    private final Map<UUID, String> adminSpeakerData = new HashMap<>();
    private final Set<UUID> speakerLocks = new HashSet<>();
    private final Map<UUID, String> randomSpeakerData = new HashMap<>();
    private final AudioCache audioCache = new AudioCache(AUDIO_CACHE_MAXIMUM_BYTES);
    private final ThreadPoolExecutor synthesisExecutor = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            runnable -> {
                Thread thread = new Thread(runnable, "ntts-synthesis");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.AbortPolicy()
    );
    private final AtomicLong acceptedRequests = new AtomicLong();
    private final AtomicLong completedRequests = new AtomicLong();
    private final AtomicLong failedRequests = new AtomicLong();
    private final AtomicLong droppedRequests = new AtomicLong();
    private final AtomicLong rejectedRequests = new AtomicLong();
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong timedRequests = new AtomicLong();
    private final AtomicLong totalLatencyMillis = new AtomicLong();

    private volatile List<String> speakers = Collections.emptyList();
    private volatile List<SuggestionEntry> speakerSuggestionEntries = Collections.emptyList();
    private volatile Map<String, String> speakerSuggestionLabels = Collections.emptyMap();
    private volatile List<String> effects = Collections.emptyList();
    private volatile VoicechatApi voicechatApi;
    private volatile VoicechatServerApi voicechatServerApi;
    private volatile NttsClient nttsClient = new NttsClient("", logger);
    private volatile String ttsMode = "local";
    private volatile String voiceMode = "random";
    private volatile String defaultSpeaker = DEFAULT_SPEAKER;
    private volatile String defaultEffect = "";
    private volatile String lastFailure = "none";
    private volatile int maximumTextLength = DEFAULT_MAXIMUM_TEXT_LENGTH;
    private volatile boolean runtimeEnabled;
    private volatile boolean playerEffectsEnabled = true;
    private volatile boolean serverReady;

    @Override
    public final String getPluginId() {
        return MOD_ID;
    }

    @Override
    public final void initialize(VoicechatApi api) {
        voicechatApi = api;
        reloadConfiguration();
        registerPlatformEvents();
        logger.info("Initialized /N/TTS voice chat plugin");
    }

    protected abstract void registerPlatformEvents();

    @Override
    public final void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, event -> {
            voicechatServerApi = event.getVoicechat();
            serverReady = true;
            VolumeCategory category = voicechatServerApi.volumeCategoryBuilder()
                    .setId(CATEGORY_ID)
                    .setName("/N/TTS Volume")
                    .setDescription("The volume of all /N/TTS voices")
                    .build();
            voicechatServerApi.registerVolumeCategory(category);
        });
    }

    protected final void handleChat(Object nativePlayer, Collection<?> nativeOnlinePlayers, String message) {
        queueSpeech(nativePlayer, nativeOnlinePlayers, message);
    }

    protected final boolean queueSpeech(
            Object nativePlayer,
            Collection<?> nativeOnlinePlayers,
            String message
    ) {
        VoicechatApi currentApi = voicechatApi;
        NttsClient currentClient = nttsClient;
        if (!runtimeEnabled || !serverReady || currentApi == null) {
            return false;
        }
        NttsClient.ProviderState providerState = currentClient.getState();
        if (providerState != NttsClient.ProviderState.READY) {
            if (providerState == NttsClient.ProviderState.RATE_LIMITED
                    || providerState == NttsClient.ProviderState.QUOTA_EXHAUSTED) {
                droppedRequests.incrementAndGet();
            }
            return false;
        }

        int requestCharacterLimit = currentClient.getRequestCharacterLimit(maximumTextLength);
        String normalizedText = NttsClient.normalizeText(message, requestCharacterLimit);
        if (normalizedText.isEmpty()) {
            return false;
        }

        ServerPlayer speakerPlayer = currentApi.fromServerPlayer(nativePlayer);
        if (speakerPlayer == null) {
            return false;
        }

        List<ServerPlayer> globalTargets = Collections.emptyList();
        if ("global".equals(ttsMode) && nativeOnlinePlayers != null) {
            globalTargets = new ArrayList<>();
            for (Object nativeTarget : nativeOnlinePlayers) {
                ServerPlayer target = currentApi.fromServerPlayer(nativeTarget);
                if (target != null) {
                    globalTargets.add(target);
                }
            }
        }

        UUID playerId = speakerPlayer.getUuid();
        String speaker = selectSpeaker(playerId);
        String effect = selectEffect(playerId);
        List<ServerPlayer> capturedTargets = globalTargets;
        String capturedMode = ttsMode;
        return submitSynthesis(() -> synthesizeAndPlay(
                currentClient,
                normalizedText,
                speaker,
                effect,
                speakerPlayer,
                capturedTargets,
                capturedMode
        ));
    }

    protected final List<String> getSpeakerSuggestions(String query) {
        return fuzzySuggestions(speakerSuggestionEntries, query);
    }

    protected final String getSpeakerSuggestionLabel(String speakerId) {
        return speakerSuggestionLabels.getOrDefault(speakerId, speakerId);
    }

    protected final List<String> getEffectSuggestions(String query) {
        List<SuggestionEntry> availableEffects = new ArrayList<>(effects.size() + 1);
        availableEffects.add(SuggestionEntry.fromValue("none"));
        for (String effect : effects) {
            availableEffects.add(SuggestionEntry.fromValue(effect));
        }
        return fuzzySuggestions(availableEffects, query);
    }

    protected final List<String> getConfigurationKeySuggestions(String query, boolean includeAll) {
        List<SuggestionEntry> entries = new ArrayList<>();
        for (String key : CONFIGURATION_KEYS) {
            entries.add(SuggestionEntry.fromValue(key));
        }
        if (includeAll) {
            entries.add(SuggestionEntry.fromValue("all"));
        }
        return fuzzySuggestions(entries, query);
    }

    protected final List<String> getConfigurationValueSuggestions(String key, String query) {
        List<SuggestionEntry> entries = new ArrayList<>();
        switch (normalizeConfigurationKey(key)) {
            case "enabled":
            case "allow_player_effects":
                entries.add(SuggestionEntry.fromValue("true"));
                entries.add(SuggestionEntry.fromValue("false"));
                break;
            case "mode":
                entries.add(SuggestionEntry.fromValue("local"));
                entries.add(SuggestionEntry.fromValue("global"));
                break;
            case "voice_mode":
                entries.add(SuggestionEntry.fromValue("random"));
                entries.add(SuggestionEntry.fromValue("static"));
                break;
            case "default_speaker":
                return getSpeakerSuggestions(query);
            case "effect":
                return getEffectSuggestions(query);
            case "max_text_length":
                entries.add(SuggestionEntry.fromValue("100"));
                entries.add(SuggestionEntry.fromValue("300"));
                entries.add(SuggestionEntry.fromValue("500"));
                break;
            default:
                return Collections.emptyList();
        }
        return fuzzySuggestions(entries, query);
    }

    protected final String getConfiguredValue(String key) {
        String normalizedKey = normalizeConfigurationKey(key);
        if (!CONFIGURATION_KEYS.contains(normalizedKey)) {
            return null;
        }
        try {
            Properties config = readConfig();
            String value = config.getProperty(
                    normalizedKey,
                    defaultConfig().getProperty(normalizedKey, "")
            ).trim();
            return "effect".equals(normalizedKey) && value.isEmpty() ? "none" : value;
        } catch (IOException exception) {
            logger.error("Failed to read /N/TTS configuration", exception);
            return null;
        }
    }

    protected final synchronized boolean setConfiguredValue(String key, String value) {
        String normalizedKey = normalizeConfigurationKey(key);
        if (!CONFIGURATION_KEYS.contains(normalizedKey)) {
            return false;
        }
        try {
            String normalizedValue = validateConfigurationValue(normalizedKey, value);
            Properties config = readConfig();
            Properties backup = copyProperties(config);
            config.setProperty(normalizedKey, normalizedValue);
            return storeAndReloadConfiguration(config, backup);
        } catch (IOException | IllegalArgumentException exception) {
            logger.warn("Rejected /N/TTS configuration update for '{}': {}", normalizedKey, exception.getMessage());
            return false;
        }
    }

    protected final synchronized boolean resetConfiguredValue(String key) {
        String normalizedKey = normalizeConfigurationKey(key);
        boolean resetAll = "all".equals(normalizedKey);
        if (!resetAll && !CONFIGURATION_KEYS.contains(normalizedKey)) {
            return false;
        }
        try {
            Properties config = readConfig();
            Properties backup = copyProperties(config);
            Properties defaults = defaultConfig();
            if (resetAll) {
                for (String configurableKey : CONFIGURATION_KEYS) {
                    config.setProperty(configurableKey, defaults.getProperty(configurableKey));
                }
            } else {
                config.setProperty(normalizedKey, defaults.getProperty(normalizedKey));
            }
            return storeAndReloadConfiguration(config, backup);
        } catch (IOException exception) {
            logger.error("Failed to reset /N/TTS configuration", exception);
            return false;
        }
    }

    protected final synchronized boolean setSpeaker(UUID playerId, String speakerId) {
        if (speakerLocks.contains(playerId)
                || speakerId == null
                || speakerId.isBlank()
                || !speakers.contains(speakerId)) {
            return false;
        }
        if (adminSpeakerData.remove(playerId) != null) {
            savePlayerAdministrationData();
        }
        speakerData.put(playerId, speakerId);
        randomSpeakerData.remove(playerId);
        return true;
    }

    protected final synchronized boolean isSpeakerLocked(UUID playerId) {
        return speakerLocks.contains(playerId);
    }

    protected final synchronized boolean setAdminSpeakers(
            Collection<UUID> playerIds,
            String speakerId
    ) {
        if (speakerId == null || speakerId.isBlank() || !speakers.contains(speakerId)) {
            return false;
        }
        for (UUID playerId : playerIds) {
            adminSpeakerData.put(playerId, speakerId);
            randomSpeakerData.remove(playerId);
        }
        savePlayerAdministrationData();
        return true;
    }

    protected final synchronized void resetAdminSpeakers(Collection<UUID> playerIds) {
        for (UUID playerId : playerIds) {
            adminSpeakerData.remove(playerId);
            speakerData.remove(playerId);
            randomSpeakerData.remove(playerId);
        }
        savePlayerData(SPEAKER_DATA_FILE, speakerData, "speaker selections");
        savePlayerAdministrationData();
    }

    protected final synchronized void setSpeakersLocked(
            Collection<UUID> playerIds,
            boolean locked
    ) {
        if (locked) {
            speakerLocks.addAll(playerIds);
        } else {
            speakerLocks.removeAll(playerIds);
        }
        savePlayerAdministrationData();
    }

    protected final synchronized String getPlayerStateLine(UUID playerId) {
        String personal = speakerData.getOrDefault(playerId, "none");
        String override = adminSpeakerData.getOrDefault(playerId, "none");
        String effect = playerEffectsEnabled
                ? effectData.getOrDefault(playerId, defaultEffect.isEmpty() ? "none" : defaultEffect)
                : defaultEffect.isEmpty() ? "none" : defaultEffect;
        return "voice: " + selectSpeaker(playerId)
                + " | personal: " + personal
                + " | operator override: " + override
                + " | locked: " + yesNo(speakerLocks.contains(playerId))
                + " | effect: " + effect;
    }

    protected final boolean arePlayerEffectsEnabled() {
        return playerEffectsEnabled;
    }

    protected final boolean setEffect(UUID playerId, String effectId) {
        if (!playerEffectsEnabled) {
            return false;
        }
        if (effectId == null || effectId.isBlank() || "none".equalsIgnoreCase(effectId)) {
            effectData.remove(playerId);
            return true;
        }
        if (!effects.contains(effectId)) {
            return false;
        }
        effectData.put(playerId, effectId);
        return true;
    }

    private String selectSpeaker(UUID playerId) {
        String adminSpeaker = adminSpeakerData.get(playerId);
        if (adminSpeaker != null) {
            return adminSpeaker;
        }
        String personalSpeaker = speakerData.get(playerId);
        if (personalSpeaker != null) {
            return personalSpeaker;
        }
        if (!"random".equals(voiceMode)) {
            return defaultSpeaker;
        }
        String assigned = randomSpeakerData.get(playerId);
        if (assigned != null) {
            return assigned;
        }
        List<String> availableSpeakers = speakers;
        if (availableSpeakers.isEmpty()) {
            return defaultSpeaker;
        }
        String selected = availableSpeakers.get(ThreadLocalRandom.current().nextInt(availableSpeakers.size()));
        randomSpeakerData.put(playerId, selected);
        return selected;
    }

    private String selectEffect(UUID playerId) {
        if (!playerEffectsEnabled) {
            return defaultEffect;
        }
        String selected = effectData.get(playerId);
        if (selected == null) {
            return defaultEffect;
        }
        List<String> availableEffects = effects;
        if (!availableEffects.isEmpty() && !availableEffects.contains(selected)) {
            return defaultEffect;
        }
        return selected;
    }

    protected final List<String> getOverviewLines() {
        return List.of(
                "NTTS - " + getServiceDisplayName(),
                "Playback: " + titleCase(ttsMode)
                        + " | Initial voice: " + titleCase(voiceMode)
                        + " | Player effects: " + (playerEffectsEnabled ? "On" : "Off"),
                "Use /ntts help for commands or /ntts status for diagnostics."
        );
    }

    protected final List<String> getHelpLines() {
        return List.of(
                "NTTS operator commands",
                "Service: /ntts status, /ntts enable, /ntts disable, /ntts reload",
                "Configuration:",
                "  /ntts get <key>",
                "  /ntts set <key> <value>",
                "  /ntts reset <key|all>",
                "Speech: /ntts test, /ntts say <message>",
                "Players:",
                "  /ntts player inspect <player>",
                "  /ntts player voice set <players> <voice>",
                "  /ntts player voice reset|lock|unlock <players>",
                "Queue: /ntts queue clear",
                "Player commands: /set_speaker <voice>, /set_effect <effect|none>"
        );
    }

    protected final List<String> getStatusLines() {
        NttsClient.ProviderState providerState = nttsClient.getState();
        int waiting = synthesisExecutor.getQueue().size();
        int active = synthesisExecutor.getActiveCount();
        long timed = timedRequests.get();
        long averageLatency = timed == 0L ? 0L : totalLatencyMillis.get() / timed;
        List<String> lines = new ArrayList<>();
        lines.add("NTTS status");
        lines.add("Service: " + getServiceDisplayName());
        lines.add("Provider: " + providerDisplayName(providerState));
        lines.add("Playback: " + titleCase(ttsMode)
                + " | Initial voice: " + titleCase(voiceMode)
                + " (default: " + defaultSpeaker + ")");
        lines.add("Effects: " + (playerEffectsEnabled ? "Player choice enabled" : "Server default only")
                + " (default: " + (defaultEffect.isEmpty() ? "none" : defaultEffect) + ")");
        lines.add(waiting == 0 && active == 0
                ? "Queue: Idle"
                : "Queue: " + waiting + " waiting, " + active + " active (capacity " + QUEUE_CAPACITY + ")");
        lines.add("Requests: " + completedRequests.get() + " completed, "
                + failedRequests.get() + " failed, "
                + droppedRequests.get() + " dropped, "
                + rejectedRequests.get() + " rejected");
        if (timed > 0L) {
            lines.add("Performance: " + averageLatency + " ms average, " + cacheHits.get() + " cache hits");
        }
        lines.add("Limits: " + nttsClient.getSafeLimitSummary());
        if (!"none".equals(lastFailure)) {
            lines.add("Last issue: " + titleCase(lastFailure));
        }
        return Collections.unmodifiableList(lines);
    }

    private String getServiceDisplayName() {
        NttsClient.ProviderState providerState = nttsClient.getState();
        if (providerState == NttsClient.ProviderState.UNCONFIGURED) {
            return "Setup required (token not configured)";
        }
        if (providerState == NttsClient.ProviderState.INVALID) {
            return "Unavailable (token invalid or expired)";
        }
        if (!runtimeEnabled) {
            return "Disabled";
        }
        if (!serverReady) {
            return "Starting (waiting for Simple Voice Chat)";
        }
        return providerState == NttsClient.ProviderState.READY
                ? "Ready"
                : providerDisplayName(providerState);
    }

    private static String providerDisplayName(NttsClient.ProviderState state) {
        switch (state) {
            case UNCONFIGURED:
                return "Token not configured";
            case READY:
                return "Connected";
            case INVALID:
                return "Token invalid or expired";
            case RATE_LIMITED:
                return "Rate limited (waiting to retry)";
            case QUOTA_EXHAUSTED:
                return "Quota exhausted";
            case UNAVAILABLE:
                return "Temporarily unavailable";
            default:
                return titleCase(state.displayName());
        }
    }

    private static String titleCase(String value) {
        if (value == null || value.isBlank()) {
            return "Unknown";
        }
        String readable = value.trim().replace('_', ' ').replace('-', ' ');
        return Character.toUpperCase(readable.charAt(0)) + readable.substring(1);
    }

    private static String yesNo(boolean value) {
        return value ? "yes" : "no";
    }

    protected final boolean setRuntimeEnabled(boolean enabled) {
        if (enabled) {
            NttsClient.ProviderState providerState = nttsClient.getState();
            if (providerState == NttsClient.ProviderState.UNCONFIGURED
                    || providerState == NttsClient.ProviderState.INVALID) {
                return false;
            }
        } else {
            clearPendingRequests();
        }
        runtimeEnabled = enabled;
        return true;
    }

    protected final int clearPendingRequests() {
        int removed = synthesisExecutor.getQueue().size();
        synthesisExecutor.getQueue().clear();
        return removed;
    }

    protected final synchronized boolean reloadConfiguration() {
        try {
            Properties config = readConfig();
            String configuredMode = config.getProperty("mode", "local").trim();
            if (!"global".equals(configuredMode) && !"local".equals(configuredMode)) {
                throw new IllegalArgumentException("mode must be 'global' or 'local'");
            }

            String configuredVoiceMode = config.getProperty("voice_mode", "random").trim();
            if (!"static".equals(configuredVoiceMode) && !"random".equals(configuredVoiceMode)) {
                throw new IllegalArgumentException("voice_mode must be 'static' or 'random'");
            }
            String configuredDefaultSpeaker = config.getProperty("default_speaker", DEFAULT_SPEAKER).trim();
            if (configuredDefaultSpeaker.isEmpty()) {
                throw new IllegalArgumentException("default_speaker must not be empty");
            }
            String configuredEffect = config.getProperty("effect", "").trim();
            boolean configuredPlayerEffects = parseBooleanProperty(
                    config,
                    "allow_player_effects",
                    true
            );
            boolean configuredEnabled = parseBooleanProperty(config, "enabled", true);

            int configuredMaximum = Integer.parseInt(
                    config.getProperty("max_text_length", Integer.toString(DEFAULT_MAXIMUM_TEXT_LENGTH)).trim());
            if (configuredMaximum < 1 || configuredMaximum > MAXIMUM_CONFIGURED_TEXT_LENGTH) {
                throw new IllegalArgumentException(
                        "max_text_length must be between 1 and " + MAXIMUM_CONFIGURED_TEXT_LENGTH);
            }

            String environmentToken = System.getenv(TOKEN_ENVIRONMENT_VARIABLE);
            String token = environmentToken == null || environmentToken.isBlank()
                    ? config.getProperty("token", "")
                    : environmentToken;
            NttsClient replacementClient = new NttsClient(token, logger);

            ttsMode = configuredMode;
            voiceMode = configuredVoiceMode;
            defaultSpeaker = configuredDefaultSpeaker;
            defaultEffect = configuredEffect;
            playerEffectsEnabled = configuredPlayerEffects;
            randomSpeakerData.clear();
            maximumTextLength = configuredMaximum;
            nttsClient = replacementClient;
            runtimeEnabled = configuredEnabled
                    && replacementClient.getState() != NttsClient.ProviderState.UNCONFIGURED;
            lastFailure = replacementClient.getState() == NttsClient.ProviderState.UNCONFIGURED
                    ? "credential-unconfigured"
                    : "none";
            audioCache.clear();
            speakers = Collections.emptyList();
            speakerSuggestionEntries = Collections.emptyList();
            speakerSuggestionLabels = Collections.emptyMap();
            effects = Collections.emptyList();
            if (replacementClient.canRequest()) {
                submitBackground(() -> {
                    replacementClient.refreshTokenInfo();
                    fetchCatalogs(replacementClient);
                });
            }
            logger.info(
                    "Loaded /N/TTS configuration: enabled={}, mode={}, voiceMode={}, playerEffects={}, maxTextLength={}, credentialSource={}",
                    runtimeEnabled,
                    ttsMode,
                    voiceMode,
                    playerEffectsEnabled,
                    maximumTextLength,
                    environmentToken == null || environmentToken.isBlank() ? "config" : "environment"
            );
            return true;
        } catch (IOException | IllegalArgumentException exception) {
            runtimeEnabled = false;
            lastFailure = "configuration-invalid";
            logger.error("Invalid /N/TTS configuration; NTTS has been disabled: {}", exception.getMessage());
            return false;
        }
    }

    protected final void loadSpeakerData() {
        loadPlayerData(SPEAKER_DATA_FILE, speakerData, "speaker selections");
        loadPlayerData(EFFECT_DATA_FILE, effectData, "effect selections");
        loadPlayerAdministrationData();
    }

    protected final void saveSpeakerData() {
        savePlayerData(SPEAKER_DATA_FILE, speakerData, "speaker selections");
        savePlayerData(EFFECT_DATA_FILE, effectData, "effect selections");
        savePlayerAdministrationData();
    }

    private void loadPlayerData(Path file, Map<UUID, String> destination, String description) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            String json = Files.readString(file, StandardCharsets.UTF_8);
            Type type = new TypeToken<Map<UUID, String>>() { }.getType();
            Map<UUID, String> data = new Gson().fromJson(json, type);
            if (data != null) {
                destination.clear();
                destination.putAll(data);
            }
        } catch (Exception exception) {
            logger.error("Failed to load /N/TTS " + description, exception);
        }
    }

    private void savePlayerData(Path file, Map<UUID, String> data, String description) {
        Path temporaryFile = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            createConfigDirectory();
            Files.writeString(
                    temporaryFile,
                    new Gson().toJson(data),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
            try {
                Files.move(
                        temporaryFile,
                        file,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );
            } catch (IOException ignored) {
                Files.move(temporaryFile, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            logger.error("Failed to save /N/TTS " + description, exception);
        }
    }

    private synchronized void loadPlayerAdministrationData() {
        if (!Files.isRegularFile(PLAYER_ADMIN_DATA_FILE)) {
            return;
        }
        try {
            PlayerAdministrationData data = new Gson().fromJson(
                    Files.readString(PLAYER_ADMIN_DATA_FILE, StandardCharsets.UTF_8),
                    PlayerAdministrationData.class
            );
            adminSpeakerData.clear();
            speakerLocks.clear();
            if (data != null && data.speakerOverrides != null) {
                adminSpeakerData.putAll(data.speakerOverrides);
            }
            if (data != null && data.speakerLocks != null) {
                speakerLocks.addAll(data.speakerLocks);
            }
        } catch (Exception exception) {
            logger.error("Failed to load /N/TTS player administration data", exception);
        }
    }

    private synchronized void savePlayerAdministrationData() {
        PlayerAdministrationData data = new PlayerAdministrationData(
                new HashMap<>(adminSpeakerData),
                new HashSet<>(speakerLocks)
        );
        Path temporaryFile = PLAYER_ADMIN_DATA_FILE.resolveSibling(
                PLAYER_ADMIN_DATA_FILE.getFileName() + ".tmp"
        );
        try {
            createConfigDirectory();
            Files.writeString(
                    temporaryFile,
                    new Gson().toJson(data),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
            );
            try {
                Files.move(
                        temporaryFile,
                        PLAYER_ADMIN_DATA_FILE,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE
                );
            } catch (IOException ignored) {
                Files.move(
                        temporaryFile,
                        PLAYER_ADMIN_DATA_FILE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }
        } catch (IOException exception) {
            logger.error("Failed to save /N/TTS player administration data", exception);
        }
    }

    protected final void stopServer() {
        serverReady = false;
        voicechatServerApi = null;
        clearPendingRequests();
        saveSpeakerData();
    }

    private void synthesizeAndPlay(
            NttsClient client,
            String text,
            String speaker,
            String effect,
            ServerPlayer speakerPlayer,
            List<ServerPlayer> globalTargets,
            String mode
    ) {
        long startedAt = System.nanoTime();
        String cacheKey = speaker + '\u0000' + effect + '\u0000' + text;
        try {
            short[] samples = audioCache.get(cacheKey);
            if (samples != null) {
                cacheHits.incrementAndGet();
            } else {
                NttsClient.SynthesisResult result = client.generateTts(text, speaker, effect);
                if (!result.isSuccess()) {
                    if (result.getState() == NttsClient.ProviderState.RATE_LIMITED
                            || result.getState() == NttsClient.ProviderState.QUOTA_EXHAUSTED) {
                        droppedRequests.incrementAndGet();
                    } else {
                        failedRequests.incrementAndGet();
                        lastFailure = result.getState().displayName();
                    }
                    return;
                }
                samples = PcmAudio.fromWav(result.getAudio());
                if (samples.length == 0) {
                    failedRequests.incrementAndGet();
                    lastFailure = "empty-audio";
                    return;
                }
                audioCache.put(cacheKey, samples);
            }

            if (!serverReady || voicechatServerApi == null) {
                failedRequests.incrementAndGet();
                lastFailure = "server-unavailable";
                return;
            }
            if ("global".equals(mode)) {
                playGlobally(globalTargets, samples);
            } else {
                playLocally(speakerPlayer, samples);
            }
            completedRequests.incrementAndGet();
            lastFailure = "none";
        } catch (Exception exception) {
            failedRequests.incrementAndGet();
            lastFailure = "audio-processing";
            logger.error("Failed to process /N/TTS audio", exception);
        } finally {
            totalLatencyMillis.addAndGet(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
            timedRequests.incrementAndGet();
        }
    }

    private void playGlobally(Collection<ServerPlayer> targets, short[] samples) {
        VoicechatServerApi serverApi = voicechatServerApi;
        if (serverApi == null) {
            return;
        }

        for (ServerPlayer target : targets) {
            try {
                VoicechatConnection connection = serverApi.getConnectionOf(target);
                if (connection == null) {
                    continue;
                }
                AudioChannel channel = serverApi.createStaticAudioChannel(
                        UUID.randomUUID(),
                        target.getServerLevel(),
                        connection
                );
                startPlayer(serverApi, channel, samples);
            } catch (RuntimeException exception) {
                logger.debug("Skipped an unavailable /N/TTS listener", exception);
            }
        }
    }

    private void playLocally(ServerPlayer speaker, short[] samples) {
        VoicechatServerApi serverApi = voicechatServerApi;
        if (serverApi == null) {
            return;
        }
        EntityAudioChannel channel = serverApi.createEntityAudioChannel(UUID.randomUUID(), speaker);
        if (channel != null) {
            channel.setDistance(16.0F);
        }
        startPlayer(serverApi, channel, samples);
    }

    private void startPlayer(VoicechatServerApi serverApi, AudioChannel channel, short[] samples) {
        if (channel == null) {
            return;
        }
        channel.setCategory(CATEGORY_ID);
        OpusEncoder encoder = serverApi.createEncoder(OpusEncoderMode.AUDIO);
        AudioPlayer player = serverApi.createAudioPlayer(channel, encoder, samples);
        player.setOnStopped(encoder::close);
        player.startPlaying();
    }

    private boolean submitSynthesis(Runnable task) {
        try {
            synthesisExecutor.execute(task);
            acceptedRequests.incrementAndGet();
            return true;
        } catch (RejectedExecutionException exception) {
            long rejected = rejectedRequests.incrementAndGet();
            lastFailure = "queue-full";
            if (rejected == 1L || rejected % 100L == 0L) {
                logger.warn("/N/TTS synthesis queue is full; rejected {} request(s)", rejected);
            }
            return false;
        }
    }

    private void submitBackground(Runnable task) {
        try {
            synthesisExecutor.execute(task);
        } catch (RejectedExecutionException exception) {
            logger.warn("Could not schedule /N/TTS background task because the queue is full");
        }
    }

    private void fetchCatalogs(NttsClient client) {
        Map<String, LinkedHashSet<String>> speakerSearchTerms = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> speakerVoiceNames = new LinkedHashMap<>();
        for (NttsClient.Voice voice : client.getVoices()) {
            for (String speaker : voice.getSpeakers()) {
                LinkedHashSet<String> terms = speakerSearchTerms.computeIfAbsent(
                        speaker,
                        ignored -> new LinkedHashSet<>()
                );
                addTermAndParts(terms, voice.getName());
                addTermAndParts(terms, speaker);
                if (voice.getName() != null && !voice.getName().isBlank()) {
                    speakerVoiceNames.computeIfAbsent(speaker, ignored -> new LinkedHashSet<>())
                            .add(voice.getName().trim());
                }
            }
        }
        List<String> loadedSpeakers = new ArrayList<>(speakerSearchTerms.keySet());
        Collections.sort(loadedSpeakers);
        List<SuggestionEntry> loadedSpeakerSuggestions = new ArrayList<>(loadedSpeakers.size());
        Map<String, String> loadedSpeakerLabels = new LinkedHashMap<>();
        for (String speaker : loadedSpeakers) {
            loadedSpeakerSuggestions.add(new SuggestionEntry(
                    speaker,
                    new ArrayList<>(speakerSearchTerms.get(speaker))
            ));
            Set<String> voiceNames = speakerVoiceNames.get(speaker);
            loadedSpeakerLabels.put(
                    speaker,
                    voiceNames == null || voiceNames.isEmpty()
                            ? speaker
                            : String.join(", ", voiceNames)
            );
        }

        List<String> loadedEffects = new ArrayList<>(client.getEffects());
        Collections.sort(loadedEffects);
        if (client != nttsClient) {
            return;
        }
        speakers = Collections.unmodifiableList(loadedSpeakers);
        speakerSuggestionEntries = Collections.unmodifiableList(loadedSpeakerSuggestions);
        speakerSuggestionLabels = Collections.unmodifiableMap(loadedSpeakerLabels);
        effects = Collections.unmodifiableList(loadedEffects);
        if (!defaultEffect.isEmpty() && !loadedEffects.contains(defaultEffect)) {
            logger.warn("Configured /N/TTS effect '{}' is unavailable; using no effect", defaultEffect);
            defaultEffect = "";
        }
        logger.info(
                "Loaded {} /N/TTS speakers and {} effects",
                loadedSpeakers.size(),
                loadedEffects.size()
        );
    }

    private static void addTermAndParts(LinkedHashSet<String> terms, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String normalized = value.trim();
        terms.add(normalized);
        for (String part : normalized.split("[\\s_]+")) {
            if (!part.isBlank()) {
                terms.add(part);
            }
        }
    }

    private static List<String> fuzzySuggestions(List<SuggestionEntry> values, String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<SuggestionEntry> ranked = new ArrayList<>();
        Map<SuggestionEntry, Integer> scores = new HashMap<>();
        for (SuggestionEntry value : values) {
            int score = needle.isEmpty() ? 0 : value.score(needle);
            if (score != NO_FUZZY_MATCH) {
                ranked.add(value);
                scores.put(value, score);
            }
        }
        ranked.sort((left, right) -> {
            int scoreComparison = Integer.compare(scores.get(left), scores.get(right));
            return scoreComparison != 0
                    ? scoreComparison
                    : left.value.compareToIgnoreCase(right.value);
        });
        int resultSize = Math.min(ranked.size(), 20);
        List<String> results = new ArrayList<>(resultSize);
        for (int index = 0; index < resultSize; index++) {
            results.add(ranked.get(index).value);
        }
        return Collections.unmodifiableList(results);
    }

    private static int fuzzyScore(String candidate, String needle) {
        if (needle.isEmpty()) {
            return 0;
        }
        String haystack = candidate.trim().toLowerCase(Locale.ROOT);
        if (haystack.equals(needle)) {
            return 0;
        }
        if (haystack.startsWith(needle)) {
            return 10 + Math.min(50, haystack.length() - needle.length());
        }
        int containedAt = haystack.indexOf(needle);
        if (containedAt >= 0) {
            return 100 + containedAt * 2 + Math.min(50, haystack.length() - needle.length());
        }

        int maximumDistance;
        if (needle.length() <= 2) {
            maximumDistance = 0;
        } else if (needle.length() <= 4) {
            maximumDistance = 1;
        } else if (needle.length() <= 7) {
            maximumDistance = 2;
        } else {
            maximumDistance = 3;
        }
        if (maximumDistance == 0 || Math.abs(haystack.length() - needle.length()) > maximumDistance) {
            return NO_FUZZY_MATCH;
        }
        int distance = levenshteinDistance(haystack, needle);
        return distance <= maximumDistance
                ? 200 + distance * 20 + Math.abs(haystack.length() - needle.length())
                : NO_FUZZY_MATCH;
    }

    private static int levenshteinDistance(String left, String right) {
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int column = 0; column <= right.length(); column++) {
            previous[column] = column;
        }
        for (int row = 1; row <= left.length(); row++) {
            current[0] = row;
            for (int column = 1; column <= right.length(); column++) {
                int substitution = previous[column - 1]
                        + (left.charAt(row - 1) == right.charAt(column - 1) ? 0 : 1);
                current[column] = Math.min(
                        Math.min(current[column - 1] + 1, previous[column] + 1),
                        substitution
                );
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }

    private static String normalizeConfigurationKey(String key) {
        return key == null ? "" : key.trim().toLowerCase(Locale.ROOT);
    }

    private String validateConfigurationValue(String key, String value) {
        String normalizedValue = value == null ? "" : value.trim();
        switch (key) {
            case "enabled":
            case "allow_player_effects":
                if (!"true".equalsIgnoreCase(normalizedValue)
                        && !"false".equalsIgnoreCase(normalizedValue)) {
                    throw new IllegalArgumentException(key + " must be 'true' or 'false'");
                }
                return normalizedValue.toLowerCase(Locale.ROOT);
            case "mode":
                if (!"global".equals(normalizedValue) && !"local".equals(normalizedValue)) {
                    throw new IllegalArgumentException("mode must be 'global' or 'local'");
                }
                return normalizedValue;
            case "voice_mode":
                if (!"static".equals(normalizedValue) && !"random".equals(normalizedValue)) {
                    throw new IllegalArgumentException("voice_mode must be 'static' or 'random'");
                }
                return normalizedValue;
            case "default_speaker":
                if (normalizedValue.isEmpty()
                        || (!speakers.isEmpty() && !speakers.contains(normalizedValue))) {
                    throw new IllegalArgumentException("unknown default speaker");
                }
                return normalizedValue;
            case "effect":
                if ("none".equalsIgnoreCase(normalizedValue)) {
                    return "";
                }
                if (!normalizedValue.isEmpty()
                        && !effects.isEmpty()
                        && !effects.contains(normalizedValue)) {
                    throw new IllegalArgumentException("unknown effect");
                }
                return normalizedValue;
            case "max_text_length":
                int configuredMaximum = Integer.parseInt(normalizedValue);
                if (configuredMaximum < 1 || configuredMaximum > MAXIMUM_CONFIGURED_TEXT_LENGTH) {
                    throw new IllegalArgumentException(
                            "max_text_length must be between 1 and " + MAXIMUM_CONFIGURED_TEXT_LENGTH);
                }
                return Integer.toString(configuredMaximum);
            default:
                throw new IllegalArgumentException("unknown configuration key");
        }
    }

    private static boolean parseBooleanProperty(Properties config, String key, boolean defaultValue) {
        String value = config.getProperty(key, Boolean.toString(defaultValue)).trim();
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException(key + " must be 'true' or 'false'");
        }
        return Boolean.parseBoolean(value);
    }

    private static Properties copyProperties(Properties source) {
        Properties copy = new Properties();
        copy.putAll(source);
        return copy;
    }

    private boolean storeAndReloadConfiguration(Properties updated, Properties backup) throws IOException {
        writeConfig(updated);
        if (reloadConfiguration()) {
            return true;
        }
        logger.warn("Rolling back rejected /N/TTS configuration update");
        writeConfig(backup);
        reloadConfiguration();
        return false;
    }

    private Properties readConfig() throws IOException {
        if (!Files.isRegularFile(CONFIG_FILE)) {
            Properties defaults = defaultConfig();
            saveConfig(defaults);
            return defaults;
        }

        Properties config = new Properties();
        try (InputStream input = Files.newInputStream(CONFIG_FILE)) {
            config.load(input);
        }
        return config;
    }

    private Properties defaultConfig() {
        Properties properties = new Properties();
        properties.setProperty("enabled", "true");
        properties.setProperty("token", "");
        properties.setProperty("mode", "local");
        properties.setProperty("voice_mode", "random");
        properties.setProperty("default_speaker", DEFAULT_SPEAKER);
        properties.setProperty("effect", "");
        properties.setProperty("allow_player_effects", "true");
        properties.setProperty("max_text_length", Integer.toString(DEFAULT_MAXIMUM_TEXT_LENGTH));
        return properties;
    }

    private void saveConfig(Properties properties) throws IOException {
        createConfigDirectory();
        try (OutputStream output = Files.newOutputStream(
                CONFIG_FILE,
                StandardOpenOption.CREATE_NEW)) {
            properties.store(output, "/N/TTS configuration; NTTS_TOKEN overrides token");
        }
    }

    private void writeConfig(Properties properties) throws IOException {
        createConfigDirectory();
        Path temporaryFile = CONFIG_FILE.resolveSibling(CONFIG_FILE.getFileName() + ".tmp");
        try (OutputStream output = Files.newOutputStream(
                temporaryFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            properties.store(output, "/N/TTS configuration; NTTS_TOKEN overrides token");
        }
        try {
            Files.move(
                    temporaryFile,
                    CONFIG_FILE,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
            );
        } catch (IOException ignored) {
            Files.move(temporaryFile, CONFIG_FILE, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void createConfigDirectory() throws IOException {
        Files.createDirectories(CONFIG_FILE.getParent());
    }

    private static final class PlayerAdministrationData {
        private Map<UUID, String> speakerOverrides;
        private Set<UUID> speakerLocks;

        private PlayerAdministrationData() {
            speakerOverrides = new HashMap<>();
            speakerLocks = new HashSet<>();
        }

        private PlayerAdministrationData(
                Map<UUID, String> speakerOverrides,
                Set<UUID> speakerLocks
        ) {
            this.speakerOverrides = speakerOverrides;
            this.speakerLocks = speakerLocks;
        }
    }

    private static final class SuggestionEntry {
        private final String value;
        private final List<String> searchTerms;

        private SuggestionEntry(String value, List<String> searchTerms) {
            this.value = value;
            this.searchTerms = searchTerms;
        }

        private static SuggestionEntry fromValue(String value) {
            LinkedHashSet<String> terms = new LinkedHashSet<>();
            addTermAndParts(terms, value);
            return new SuggestionEntry(value, new ArrayList<>(terms));
        }

        private int score(String needle) {
            int best = fuzzyScore(value, needle);
            for (String term : searchTerms) {
                int termScore = fuzzyScore(term, needle);
                if (termScore != NO_FUZZY_MATCH) {
                    best = Math.min(best, termScore + SEARCH_TERM_PENALTY);
                }
            }
            return best;
        }
    }

    private static final class AudioCache {
        private final long maximumBytes;
        private final LinkedHashMap<String, short[]> entries = new LinkedHashMap<>(16, 0.75F, true);
        private long usedBytes;

        private AudioCache(long maximumBytes) {
            this.maximumBytes = maximumBytes;
        }

        private synchronized short[] get(String key) {
            return entries.get(key);
        }

        private synchronized void put(String key, short[] samples) {
            long sampleBytes = samples.length * 2L;
            if (sampleBytes > maximumBytes) {
                return;
            }
            short[] previous = entries.put(key, samples);
            if (previous != null) {
                usedBytes -= previous.length * 2L;
            }
            usedBytes += sampleBytes;
            Iterator<Map.Entry<String, short[]>> iterator = entries.entrySet().iterator();
            while (usedBytes > maximumBytes && iterator.hasNext()) {
                Map.Entry<String, short[]> eldest = iterator.next();
                usedBytes -= eldest.getValue().length * 2L;
                iterator.remove();
            }
        }

        private synchronized void clear() {
            entries.clear();
            usedBytes = 0L;
        }
    }
}
