package com.cypherv6;

import com.cypherv6.client.ClientEvents;
import com.cypherv6.module.ModuleManager;
import com.cypherv6.modules.client.Hud;
import com.cypherv6.modules.client.Notifications;
import com.cypherv6.modules.combat.KillAura;
import com.cypherv6.modules.combat.Tracer;
import com.cypherv6.modules.combat.TriggerBot;
import com.cypherv6.modules.movement.AutoSprint;
import com.cypherv6.modules.render.CustomFov;
import com.cypherv6.modules.render.FpsBooster;
import com.cypherv6.modules.render.Fullbright;
import com.cypherv6.modules.render.Nametags;
import com.cypherv6.modules.render.NoRain;
import com.mojang.logging.LogUtils;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

/**
 * CypherV6 - a legit client for Minecraft 26.3 (Forge).
 * <p>
 * Press RIGHT SHIFT in game to open the click GUI.
 */
@Mod(CypherV6.MODID)
public final class CypherV6 {
    public static final String MODID = "cypherv6";
    public static final Logger LOGGER = LogUtils.getLogger();
    public static final ModuleManager MODULES = new ModuleManager();

    public CypherV6() {
        registerModules();
        if (FMLEnvironment.dist == Dist.CLIENT) {
            ClientEvents.register();
        }
        LOGGER.info("CypherV6 ready - press RIGHT SHIFT in game to open the GUI.");
    }

    private void registerModules() {
        MODULES.register(new CustomFov());
        MODULES.register(new Fullbright());
        MODULES.register(new Nametags());
        MODULES.register(new NoRain());
        MODULES.register(new FpsBooster());
        MODULES.register(new Hud());
        MODULES.register(new Notifications());
        MODULES.register(new KillAura());
        MODULES.register(new TriggerBot());
        MODULES.register(new Tracer());
        MODULES.register(new AutoSprint());
    }
}
