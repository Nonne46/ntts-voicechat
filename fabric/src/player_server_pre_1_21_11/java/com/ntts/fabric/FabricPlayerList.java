package com.ntts.fabric;

import net.minecraft.server.level.ServerPlayer;

import java.util.List;

final class FabricPlayerList {
    private FabricPlayerList() {
    }

    static List<ServerPlayer> get(ServerPlayer player) {
        return player.getServer().getPlayerList().getPlayers();
    }
}
