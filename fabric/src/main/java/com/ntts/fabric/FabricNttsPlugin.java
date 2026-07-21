package com.ntts.fabric;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.ntts.common.NttsPluginCore;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public final class FabricNttsPlugin extends NttsPluginCore {
    private static final int MAXIMUM_ADMIN_TARGETS = 100;
    @Override
    protected void registerPlatformEvents() {
        ServerMessageEvents.CHAT_MESSAGE.register((message, player, parameters) ->
                handleChat(
                        player,
                        FabricPlayerList.get(player),
                        FabricChatText.get(message)
                ));
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                registerCommands(dispatcher));
        ServerLifecycleEvents.SERVER_STARTED.register(server -> loadSpeakerData());
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> stopServer());
    }

    private static List<UUID> playerIds(Collection<ServerPlayer> players) {
        List<UUID> playerIds = new ArrayList<>(players.size());
        for (ServerPlayer player : players) {
            playerIds.add(player.getUUID());
        }
        return playerIds;
    }

    private static void sendLines(CommandSourceStack source, List<String> lines) {
        for (String line : lines) {
            source.sendSystemMessage(Component.literal(line));
        }
    }

    private void addSpeakerSuggestions(SuggestionsBuilder builder) {
        for (String speaker : getSpeakerSuggestions(builder.getRemaining())) {
            String label = getSpeakerSuggestionLabel(speaker);
            if (speaker.equals(label)) {
                builder.suggest(speaker);
            } else {
                builder.suggest(speaker, new LiteralMessage(label));
            }
        }
    }

    private void addConfigurationValueSuggestions(String key, SuggestionsBuilder builder) {
        if ("default_speaker".equalsIgnoreCase(key.trim())) {
            addSpeakerSuggestions(builder);
            return;
        }
        getConfigurationValueSuggestions(key, builder.getRemaining()).forEach(builder::suggest);
    }

    private void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("ntts")
                        .requires(FabricCommandPermissions::isOperator)
                        .executes(context -> {
                            sendLines(context.getSource(), getOverviewLines());
                            return 1;
                        })
                        .then(Commands.literal("status")
                                .executes(context -> {
                                    sendLines(context.getSource(), getStatusLines());
                                    return 1;
                                }))
                        .then(Commands.literal("enable")
                                .executes(context -> {
                                    if (!setRuntimeEnabled(true)) {
                                        context.getSource().sendFailure(Component.literal(
                                                "NTTS cannot be enabled until a valid credential is configured"));
                                        return 0;
                                    }
                                    context.getSource().sendSystemMessage(Component.literal(
                                            "NTTS enabled temporarily; reload restores the configured value"));
                                    return 1;
                                }))
                        .then(Commands.literal("disable")
                                .executes(context -> {
                                    setRuntimeEnabled(false);
                                    context.getSource().sendSystemMessage(Component.literal(
                                            "NTTS disabled temporarily; reload restores the configured value"));
                                    return 1;
                                }))
                        .then(Commands.literal("reload")
                                .executes(context -> {
                                    if (!reloadConfiguration()) {
                                        context.getSource().sendFailure(Component.literal(
                                                "Invalid NTTS configuration; see the server log"));
                                        return 0;
                                    }
                                    context.getSource().sendSystemMessage(Component.literal(
                                            "NTTS configuration reloaded"));
                                    return 1;
                                }))
                        .then(Commands.literal("help")
                                .executes(context -> {
                                    sendLines(context.getSource(), getHelpLines());
                                    return 1;
                                }))
                        .then(Commands.literal("get")
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .suggests((context, builder) -> {
                                            getConfigurationKeySuggestions(builder.getRemaining(), false)
                                                    .forEach(builder::suggest);
                                            return builder.buildFuture();
                                        })
                                        .executes(context -> {
                                            String key = StringArgumentType.getString(context, "key");
                                            String value = getConfiguredValue(key);
                                            if (value == null) {
                                                context.getSource().sendFailure(Component.literal(
                                                        "Unknown or unreadable NTTS configuration key: " + key));
                                                return 0;
                                            }
                                            context.getSource().sendSystemMessage(Component.literal(
                                                    "NTTS " + key + "=" + value));
                                            return 1;
                                        })))
                        .then(Commands.literal("set")
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .suggests((context, builder) -> {
                                            getConfigurationKeySuggestions(builder.getRemaining(), false)
                                                    .forEach(builder::suggest);
                                            return builder.buildFuture();
                                        })
                                        .then(Commands.argument("value", StringArgumentType.word())
                                                .suggests((context, builder) -> {
                                                    addConfigurationValueSuggestions(
                                                            StringArgumentType.getString(context, "key"),
                                                            builder
                                                    );
                                                    return builder.buildFuture();
                                                })
                                                .executes(context -> {
                                                    String key = StringArgumentType.getString(context, "key");
                                                    String value = StringArgumentType.getString(context, "value");
                                                    if (!setConfiguredValue(key, value)) {
                                                        context.getSource().sendFailure(Component.literal(
                                                                "Invalid NTTS configuration value for " + key));
                                                        return 0;
                                                    }
                                                    context.getSource().sendSystemMessage(Component.literal(
                                                            "NTTS " + key + "=" + getConfiguredValue(key)));
                                                    return 1;
                                                }))))
                        .then(Commands.literal("reset")
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .suggests((context, builder) -> {
                                            getConfigurationKeySuggestions(builder.getRemaining(), true)
                                                    .forEach(builder::suggest);
                                            return builder.buildFuture();
                                        })
                                        .executes(context -> {
                                            String key = StringArgumentType.getString(context, "key");
                                            if (!resetConfiguredValue(key)) {
                                                context.getSource().sendFailure(Component.literal(
                                                        "Could not reset NTTS configuration key: " + key));
                                                return 0;
                                            }
                                            context.getSource().sendSystemMessage(Component.literal(
                                                    "Reset NTTS configuration " + key));
                                            return 1;
                                        })))
                        .then(Commands.literal("player")
                                .then(Commands.literal("inspect")
                                        .then(Commands.argument("target", EntityArgument.player())
                                                .executes(context -> {
                                                    ServerPlayer target = EntityArgument.getPlayer(
                                                            context,
                                                            "target"
                                                    );
                                                    context.getSource().sendSystemMessage(Component.literal(
                                                            "NTTS player - " + target.getName().getString()));
                                                    context.getSource().sendSystemMessage(Component.literal(
                                                            getPlayerStateLine(target.getUUID())));
                                                    return 1;
                                                })))
                                .then(Commands.literal("voice")
                                        .then(Commands.literal("set")
                                                .then(Commands.argument("targets", EntityArgument.players())
                                                        .then(Commands.argument(
                                                                        "speaker",
                                                                        StringArgumentType.word()
                                                                )
                                                                .suggests((context, builder) -> {
                                                                    addSpeakerSuggestions(builder);
                                                                    return builder.buildFuture();
                                                                })
                                                                .executes(context -> {
                                                                    Collection<ServerPlayer> targets =
                                                                            EntityArgument.getPlayers(
                                                                                    context,
                                                                                    "targets"
                                                                            );
                                                                    if (targets.size() > MAXIMUM_ADMIN_TARGETS) {
                                                                        context.getSource().sendFailure(Component.literal(
                                                                                "Too many targets; maximum is "
                                                                                        + MAXIMUM_ADMIN_TARGETS));
                                                                        return 0;
                                                                    }
                                                                    String speaker = StringArgumentType.getString(
                                                                            context,
                                                                            "speaker"
                                                                    );
                                                                    if (!setAdminSpeakers(playerIds(targets), speaker)) {
                                                                        context.getSource().sendFailure(Component.literal(
                                                                                "Invalid speaker name: " + speaker));
                                                                        return 0;
                                                                    }
                                                                    for (ServerPlayer target : targets) {
                                                                        target.sendSystemMessage(Component.literal(
                                                                                "An operator set your NTTS voice to "
                                                                                        + speaker));
                                                                    }
                                                                    logger.info(
                                                                            "/N/TTS operator {} set voice {} for {} player(s)",
                                                                            context.getSource().getTextName(),
                                                                            speaker,
                                                                            targets.size()
                                                                    );
                                                                    context.getSource().sendSystemMessage(Component.literal(
                                                                            "Set NTTS voice to " + speaker + " for "
                                                                                    + targets.size() + " player(s)"));
                                                                    return targets.size();
                                                                }))))
                                        .then(Commands.literal("reset")
                                                .then(Commands.argument("targets", EntityArgument.players())
                                                        .executes(context -> {
                                                            Collection<ServerPlayer> targets =
                                                                    EntityArgument.getPlayers(context, "targets");
                                                            if (targets.size() > MAXIMUM_ADMIN_TARGETS) {
                                                                context.getSource().sendFailure(Component.literal(
                                                                        "Too many targets; maximum is "
                                                                                + MAXIMUM_ADMIN_TARGETS));
                                                                return 0;
                                                            }
                                                            resetAdminSpeakers(playerIds(targets));
                                                            for (ServerPlayer target : targets) {
                                                                target.sendSystemMessage(Component.literal(
                                                                        "An operator reset your NTTS voice"));
                                                            }
                                                            logger.info(
                                                                    "/N/TTS operator {} reset voice for {} player(s)",
                                                                    context.getSource().getTextName(),
                                                                    targets.size()
                                                            );
                                                            context.getSource().sendSystemMessage(Component.literal(
                                                                    "Reset NTTS voice for " + targets.size()
                                                                            + " player(s)"));
                                                            return targets.size();
                                                        })))
                                        .then(Commands.literal("lock")
                                                .then(Commands.argument("targets", EntityArgument.players())
                                                        .executes(context -> {
                                                            Collection<ServerPlayer> targets =
                                                                    EntityArgument.getPlayers(context, "targets");
                                                            if (targets.size() > MAXIMUM_ADMIN_TARGETS) {
                                                                context.getSource().sendFailure(Component.literal(
                                                                        "Too many targets; maximum is "
                                                                                + MAXIMUM_ADMIN_TARGETS));
                                                                return 0;
                                                            }
                                                            setSpeakersLocked(playerIds(targets), true);
                                                            for (ServerPlayer target : targets) {
                                                                target.sendSystemMessage(Component.literal(
                                                                        "An operator locked your NTTS voice selection"));
                                                            }
                                                            logger.info(
                                                                    "/N/TTS operator {} locked voice for {} player(s)",
                                                                    context.getSource().getTextName(),
                                                                    targets.size()
                                                            );
                                                            context.getSource().sendSystemMessage(Component.literal(
                                                                    "Locked NTTS voice for " + targets.size()
                                                                            + " player(s)"));
                                                            return targets.size();
                                                        })))
                                        .then(Commands.literal("unlock")
                                                .then(Commands.argument("targets", EntityArgument.players())
                                                        .executes(context -> {
                                                            Collection<ServerPlayer> targets =
                                                                    EntityArgument.getPlayers(context, "targets");
                                                            if (targets.size() > MAXIMUM_ADMIN_TARGETS) {
                                                                context.getSource().sendFailure(Component.literal(
                                                                        "Too many targets; maximum is "
                                                                                + MAXIMUM_ADMIN_TARGETS));
                                                                return 0;
                                                            }
                                                            setSpeakersLocked(playerIds(targets), false);
                                                            for (ServerPlayer target : targets) {
                                                                target.sendSystemMessage(Component.literal(
                                                                        "An operator unlocked your NTTS voice selection"));
                                                            }
                                                            logger.info(
                                                                    "/N/TTS operator {} unlocked voice for {} player(s)",
                                                                    context.getSource().getTextName(),
                                                                    targets.size()
                                                            );
                                                            context.getSource().sendSystemMessage(Component.literal(
                                                                    "Unlocked NTTS voice for " + targets.size()
                                                                            + " player(s)"));
                                                            return targets.size();
                                                        })))))
                        .then(Commands.literal("test")
                                .executes(context -> {
                                    ServerPlayer player = context.getSource().getPlayerOrException();
                                    if (!queueSpeech(
                                            player,
                                            FabricPlayerList.get(player),
                                            "This is an NTTS voice test."
                                    )) {
                                        context.getSource().sendFailure(Component.literal(
                                                "Could not queue the NTTS voice test; check /ntts status"));
                                        return 0;
                                    }
                                    context.getSource().sendSystemMessage(Component.literal(
                                            "Queued NTTS voice test"));
                                    return 1;
                                }))
                        .then(Commands.literal("say")
                                .then(Commands.argument("message", StringArgumentType.greedyString())
                                        .executes(context -> {
                                            ServerPlayer player = context.getSource().getPlayerOrException();
                                            String message = StringArgumentType.getString(context, "message");
                                            if (!queueSpeech(player, FabricPlayerList.get(player), message)) {
                                                context.getSource().sendFailure(Component.literal(
                                                        "Could not queue the NTTS announcement; check /ntts status"));
                                                return 0;
                                            }
                                            context.getSource().sendSystemMessage(Component.literal(
                                                    "Queued NTTS announcement"));
                                            return 1;
                                        })))
                        .then(Commands.literal("queue")
                                .then(Commands.literal("clear")
                                        .executes(context -> {
                                            int removed = clearPendingRequests();
                                            context.getSource().sendSystemMessage(Component.literal(
                                                    "Cleared " + removed + " queued NTTS request(s)"));
                                            return 1;
                                        })))
        );
        dispatcher.register(
                Commands.literal("set_speaker")
                        .then(Commands.argument("speakerID", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    addSpeakerSuggestions(builder);
                                    return builder.buildFuture();
                                })
                                .executes(context -> {
                                    ServerPlayer player = context.getSource().getPlayerOrException();
                                    String speakerId = StringArgumentType.getString(context, "speakerID");
                                    if (isSpeakerLocked(player.getUUID())) {
                                        context.getSource().sendFailure(Component.literal(
                                                "Your NTTS voice selection is locked by an operator"));
                                        return 0;
                                    }
                                    if (!setSpeaker(player.getUUID(), speakerId)) {
                                        context.getSource().sendFailure(
                                                Component.literal("Invalid speaker name: " + speakerId));
                                        return 0;
                                    }
                                    context.getSource().sendSystemMessage(
                                            Component.literal("Speaker set to " + speakerId));
                                    return 1;
                                }))
        );
        dispatcher.register(
                Commands.literal("set_effect")
                        .then(Commands.argument("effect", StringArgumentType.word())
                                .suggests((context, builder) -> {
                                    getEffectSuggestions(builder.getRemaining()).forEach(builder::suggest);
                                    return builder.buildFuture();
                                })
                                .executes(context -> {
                                    ServerPlayer player = context.getSource().getPlayerOrException();
                                    String effectId = StringArgumentType.getString(context, "effect");
                                    if (!arePlayerEffectsEnabled()) {
                                        context.getSource().sendFailure(Component.literal(
                                                "Player-selected NTTS effects are disabled by the server"));
                                        return 0;
                                    }
                                    if (!setEffect(player.getUUID(), effectId)) {
                                        context.getSource().sendFailure(
                                                Component.literal("Invalid effect name: " + effectId));
                                        return 0;
                                    }
                                    context.getSource().sendSystemMessage(Component.literal(
                                            "none".equalsIgnoreCase(effectId)
                                                    ? "NTTS effect cleared"
                                                    : "NTTS effect set to " + effectId));
                                    return 1;
                                }))
        );
    }
}
