package com.ntts.forge;

import net.minecraft.commands.CommandSourceStack;

final class ForgeCommandPermissions {
    private ForgeCommandPermissions() {
    }

    static boolean isOperator(CommandSourceStack source) {
        return source.hasPermission(2);
    }
}
