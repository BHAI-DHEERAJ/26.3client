package com.cypherv6.modules.movement;

import com.cypherv6.module.Category;
import com.cypherv6.module.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;

/**
 * Keeps you sprinting whenever you are moving forward.
 */
public class AutoSprint extends Module {

    public AutoSprint() {
        super("AutoSprint", Category.MOVEMENT, "Keeps you sprinting while moving forward");
    }

    @Override
    public void onTick() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player != null && minecraft.options.keyUp.isDown() && !player.isShiftKeyDown()
                && player.getFoodData().getFoodLevel() > 6) {
            player.setSprinting(true);
        }
    }
}
