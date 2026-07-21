package com.ntts.fabric;

import net.minecraft.commands.CommandSourceStack;

final class FabricCommandPermissions {
    private FabricCommandPermissions() {
    }

    static boolean isOperator(CommandSourceStack source) {
        return source.hasPermission(2);
    }
}
