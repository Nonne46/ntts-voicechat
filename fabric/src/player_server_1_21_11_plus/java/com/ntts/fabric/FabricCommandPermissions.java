package com.ntts.fabric;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.permissions.Permissions;

final class FabricCommandPermissions {
    private FabricCommandPermissions() {
    }

    static boolean isOperator(CommandSourceStack source) {
        return source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }
}
