package com.ntts.forge;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

final class ForgeEventRegistration {
    private final ForgeNttsPlugin plugin;

    private ForgeEventRegistration(ForgeNttsPlugin plugin) {
        this.plugin = plugin;
    }

    static void register(ForgeNttsPlugin plugin) {
        MinecraftForge.EVENT_BUS.register(new ForgeEventRegistration(plugin));
    }

    @SubscribeEvent
    public void onServerChat(ServerChatEvent event) {
        plugin.onServerChat(event);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        plugin.onRegisterCommands(event);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        plugin.onServerStarted(event);
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        plugin.onServerStopping(event);
    }
}
