package com.ntts;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import de.maxhenkel.voicechat.api.*;
import de.maxhenkel.voicechat.api.audiochannel.AudioChannel;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.EntityAudioChannel;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import de.maxhenkel.voicechat.api.opus.OpusEncoder;
import de.maxhenkel.voicechat.api.opus.OpusEncoderMode;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import java.io.*;
import java.util.*;
import javax.sound.sampled.AudioInputStream;

@ForgeVoicechatPlugin
public class nttsPlugin implements VoicechatPlugin {
    private static final Logger logger = LoggerFactory.getLogger("ntts-plugin");

    private static final String NTTS_CATEGORY = "ntts_cat";
    private VoicechatServerApi voicechatServerApi;
    private nttsClient nttsClient;

    private final Map<UUID, String> speakerData = new HashMap<>();
    private final File dataFile = new File("config/ntts_speaker_data.json");
    private final File configFile = new File("config/ntts.properties");
    private final List<String> speakers = new ArrayList<>();

    private String token = "your_default_token";
    private String ttsMode = "global";

    @Override
    public String getPluginId() {
        return nttsMod.MOD_ID;
    }

    @Override
    public void initialize(VoicechatApi api) {
        MinecraftForge.EVENT_BUS.register(this);

        nttsMod.LOGGER.info("Hello from /n/tts plugin!!");

        loadConfig();
        nttsClient = new nttsClient(token);
    }

    @SubscribeEvent
    public void onServerChat(ServerChatEvent event) {
        ServerPlayer player = event.getPlayer();
        String playerMessage = event.getMessage().getString();

        nttsMod.LOGGER.info("Received message: " + playerMessage + " from " + player.getName().getString());

        String speaker = speakerData.getOrDefault(player.getUUID(), "narrator_d3");
        byte[] audioData = generateTTS(playerMessage, speaker);

        if (audioData != null) {
            playLocationalSound(player, audioData);
        }
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, event -> {
            voicechatServerApi = event.getVoicechat();

            if ("global".equals(ttsMode)) {
                VolumeCategory  ttsCat = voicechatServerApi.volumeCategoryBuilder()
                        .setId(NTTS_CATEGORY)
                        .setName("/N/TTS Volume")
                        .setDescription("The volume of all /N/TTS voices")
                        .build();

                voicechatServerApi.registerVolumeCategory(ttsCat);
            }
        });
    }

    private byte[] generateTTS(String text, String speaker) {
        return nttsClient.generateTTS(text, speaker);
    }

    private void playLocationalSound(ServerPlayer player, byte[] audioData) {
        if (voicechatServerApi == null) return;

        short[] audioDataPCM = convertToPCM(audioData);
        OpusEncoder encoder = voicechatServerApi.createEncoder(OpusEncoderMode.AUDIO);

        if ("global".equals(ttsMode)) {
            for (ServerPlayer targetPlayer : Objects.requireNonNull(player.getServer()).getPlayerList().getPlayers()) {
                var voicePlayer = voicechatServerApi.fromServerPlayer(targetPlayer);
                AudioChannel targetChannel = voicechatServerApi.createStaticAudioChannel(UUID.randomUUID(), voicePlayer.getServerLevel(), voicechatServerApi.getConnectionOf(voicePlayer));

                if (targetChannel != null) {
                    targetChannel.setCategory(NTTS_CATEGORY);
                    AudioPlayer ttsPlayer = voicechatServerApi.createAudioPlayer(targetChannel, encoder, audioDataPCM);
                    ttsPlayer.startPlaying();
                }
            }
        } else {
            var voicePlayer = voicechatServerApi.fromServerPlayer(player);
            EntityAudioChannel channel = voicechatServerApi.createEntityAudioChannel(player.getUUID(), voicePlayer);

            if (channel != null) {
                channel.setDistance(16.0f);
                channel.setCategory(NTTS_CATEGORY);
                AudioPlayer ttsPlayer = voicechatServerApi.createAudioPlayer(channel, encoder, audioDataPCM);
                ttsPlayer.startPlaying();
            }
        }
    }

    private short[] convertToPCM(byte[] audioData) {
        try {
            ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(audioData);
            AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(byteArrayInputStream);

            AudioFormat format = audioInputStream.getFormat();

            AudioFormat pcmFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    format.getSampleRate(),
                    16,
                    format.getChannels(),
                    format.getChannels() * 2,
                    format.getSampleRate(),
                    false
            );

            AudioInputStream convertedAudioInputStream = AudioSystem.getAudioInputStream(pcmFormat, audioInputStream);

            byte[] buffer = new byte[4096];
            List<Short> samples = new ArrayList<>();
            int numBytesRead;
            while ((numBytesRead = convertedAudioInputStream.read(buffer)) != -1) {
                int numSamples = numBytesRead / 2;
                for (int i = 0; i < numSamples; i++) {
                    int low = buffer[i * 2] & 0xff;
                    int high = buffer[i * 2 + 1] << 8;
                    short sample = (short) (high | low);
                    samples.add(sample);
                }
            }

            short[] result = new short[samples.size()];
            for (int i = 0; i < result.length; i++) {
                result[i] = samples.get(i);
            }
            return result;

        } catch (Exception e) {
            nttsMod.LOGGER.error("Error converting to PCM", e);
            return new short[0];
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        registerCommands(event.getDispatcher());
    }

    private void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("set_speaker")
                        .then(Commands.argument("speakerID", StringArgumentType.string())
                                .suggests((context, builder) -> {
                                    speakers.forEach(builder::suggest);
                                    return builder.buildFuture();
                                })
                                .executes(this::setSpeaker)
                        )
        );
    }

    private int setSpeaker(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String speakerID = StringArgumentType.getString(context, "speakerID");

        if (!speakerID.isBlank() && speakers.contains(speakerID)) {
            speakerData.put(player.getUUID(), speakerID);
            context.getSource().sendSuccess(() -> Component.literal("Speaker set to " + speakerID), false);
        } else {
            context.getSource().sendFailure(Component.literal("Invalid speaker name: " + speakerID));
            return 1;
        }

        return 0;
    }

    private void loadSpeakerData() {
        if (dataFile.exists()) {
            try {
                String json = new String(java.nio.file.Files.readAllBytes(dataFile.toPath()));
                Map<UUID, String> data = new Gson().fromJson(json, new TypeToken<Map<UUID, String>>() {}.getType());
                speakerData.putAll(data);
            } catch (IOException e) {
                logger.error("Error loading speaker data", e);
            }
        }
    }

    private void saveSpeakerData() {
        try (FileWriter writer = new FileWriter(dataFile)) {
            String json = new Gson().toJson(speakerData);
            writer.write(json);
        } catch (IOException e) {
            logger.error("Error saving speaker data", e);
        }
    }

    private void fetchSpeakers() {
        List<nttsClient.Voice> voices = nttsClient.getSpeakers();

        speakers.clear();
        for (nttsClient.Voice voice : voices) {
            speakers.addAll(voice.getSpeakers());
        }
    }

    private void loadConfig() {
        if (!configFile.exists()) {
            saveDefaultConfig();
        }

        try (FileInputStream fis = new FileInputStream(configFile)) {
            Properties properties = new Properties();
            properties.load(fis);

            token = properties.getProperty("token", "your_default_token");
            ttsMode = properties.getProperty("mode", "global");

            if (token.isEmpty()) {
                throw new IllegalArgumentException("NTTS Token is empty");
            }

            if (!"global".equals(ttsMode) && !"local".equals(ttsMode)) {
                throw new IllegalArgumentException("Invalid TTS mode. Use 'global' or 'local'");
            }

        } catch (IOException | IllegalArgumentException e) {
            logger.error("Error reading config file", e);
            saveDefaultConfig();
        }
    }

    private void saveDefaultConfig() {
        try (FileOutputStream fos = new FileOutputStream(configFile)) {
            Properties properties = new Properties();
            properties.setProperty("token", "your_token_here");
            properties.setProperty("mode", "global");
            properties.store(fos, null);
        } catch (IOException e) {
            logger.error("Error saving default config", e);
        }
    }
}
