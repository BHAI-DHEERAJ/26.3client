package com.cypherv6.modules.render;

import com.cypherv6.module.Category;
import com.cypherv6.module.Module;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * Removes rain and thunder client-side by pinning the level's weather level to zero.
 */
public class NoRain extends Module {

    public NoRain() {
        super("NoRain", Category.RENDER, "Disables rain and thunder client-side");
    }

    @Override
    public void onTick() {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null) {
            level.setRainLevel(0.0F);
            level.setThunderLevel(0.0F);
        }
    }
}
