package com.cypherv6.modules.combat;

import com.cypherv6.module.Category;
import com.cypherv6.module.Module;
import com.cypherv6.setting.BooleanSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;

/**
 * Attacks whatever entity is currently under your crosshair.
 */
public class TriggerBot extends Module {

    private final BooleanSetting players = register(new BooleanSetting("Players", true));
    private final BooleanSetting mobs = register(new BooleanSetting("Mobs", true));
    private final BooleanSetting onlyCharged = register(new BooleanSetting("Only Charged", true));

    public TriggerBot() {
        super("TriggerBot", Category.COMBAT, "Attacks whatever is in your crosshair");
    }

    @Override
    public void onTick() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.gameMode == null) {
            return;
        }
        if (!(minecraft.hitResult instanceof EntityHitResult hit)) {
            return;
        }
        Entity target = hit.getEntity();
        if (target == player || !target.isAlive()) {
            return;
        }
        if (target instanceof Player) {
            if (!players.isEnabled()) {
                return;
            }
        } else if (!(target instanceof LivingEntity) || !mobs.isEnabled()) {
            return;
        }
        if (onlyCharged.isEnabled() && player.getAttackStrengthScale(0.0F) < 1.0F) {
            return;
        }
        minecraft.gameMode.attack(player, target);
        player.resetAttackStrengthTicker();
    }
}
