package com.ntts.forge;

import net.minecraft.server.level.ServerPlayer;

import java.util.List;

final class ForgePlayerList {
    private ForgePlayerList() {
    }

    static List<ServerPlayer> get(ServerPlayer player) {
        return player.getServer().getPlayerList().getPlayers();
    }
}
