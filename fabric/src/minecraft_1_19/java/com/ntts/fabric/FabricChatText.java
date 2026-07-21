package com.ntts.fabric;

import net.minecraft.network.chat.PlayerChatMessage;

final class FabricChatText {
    private FabricChatText() {
    }

    static String get(PlayerChatMessage message) {
        return message.signedContent().plain();
    }
}
