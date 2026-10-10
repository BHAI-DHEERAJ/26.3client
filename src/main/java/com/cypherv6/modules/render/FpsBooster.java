package com.cypherv6.modules.render;

import com.cypherv6.module.Category;
import com.cypherv6.module.Module;
import com.cypherv6.setting.BooleanSetting;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.server.level.ParticleStatus;

/**
 * Raises FPS by temporarily reducing particle, cloud and entity-shadow work.
 * The original option values are saved and restored when the module is turned off.
 */
public class FpsBooster extends Module {

    private final BooleanSetting particles = register(new BooleanSetting("Minimal Particles", true));
    private final BooleanSetting clouds = register(new BooleanSetting("Disable Clouds", true));
    private final BooleanSetting shadows = register(new BooleanSetting("Disable Shadows", true));

    private ParticleStatus oldParticles;
    private CloudStatus oldClouds;
    private boolean oldShadows;
    private boolean saved;

    public FpsBooster() {
        super("FPSBooster", Category.RENDER, "Reduces particle/cloud/shadow work to raise FPS");
    }

    @Override
    protected void onEnable() {
        Options options = Minecraft.getInstance().options;
        oldParticles = options.particles().get();
        oldClouds = options.cloudStatus().get();
        oldShadows = options.entityShadows().get();
        saved = true;

        if (particles.isEnabled()) {
            options.particles().set(ParticleStatus.MINIMAL);
        }
        if (clouds.isEnabled()) {
            options.cloudStatus().set(CloudStatus.OFF);
        }
        if (shadows.isEnabled()) {
            options.entityShadows().set(false);
        }
    }

    @Override
    protected void onDisable() {
        if (!saved) {
            return;
        }
        Options options = Minecraft.getInstance().options;
        options.particles().set(oldParticles);
        options.cloudStatus().set(oldClouds);
        options.entityShadows().set(oldShadows);
        saved = false;
    }
}
