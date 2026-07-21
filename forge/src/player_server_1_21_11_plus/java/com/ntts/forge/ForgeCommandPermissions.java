package com.ntts.forge;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.permissions.Permissions;

final class ForgeCommandPermissions {
    private ForgeCommandPermissions() {
    }

    static boolean isOperator(CommandSourceStack source) {
        return source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }
}
