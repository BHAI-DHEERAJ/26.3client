package com.cypherv6.modules.combat;

import com.cypherv6.module.Category;
import com.cypherv6.module.Module;
import com.cypherv6.setting.BooleanSetting;
import com.cypherv6.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Automatically attacks the closest entity in range, optionally rotating toward it.
 * Uses the normal {@code gameMode.attack} path (no reach modification).
 */
public class KillAura extends Module {

    private final NumberSetting range = register(new NumberSetting("Range", 3.0, 1.0, 6.0, 0.1));
    private final NumberSetting cps = register(new NumberSetting("CPS", 10.0, 1.0, 20.0, 1.0));
    private final BooleanSetting players = register(new BooleanSetting("Players", true));
    private final BooleanSetting mobs = register(new BooleanSetting("Mobs", true));
    private final BooleanSetting rotate = register(new BooleanSetting("Rotate", true));
    private final BooleanSetting onlyCharged = register(new BooleanSetting("Only Charged", true));

    private long lastAttack;

    public KillAura() {
        super("KillAura", Category.COMBAT, "Attacks nearby entities");
    }

    @Override
    public void onTick() {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null || minecraft.gameMode == null) {
            return;
        }
        Entity target = findTarget(minecraft, player);
        if (target == null) {
            return;
        }
        if (onlyCharged.isEnabled() && player.getAttackStrengthScale(0.0F) < 1.0F) {
            return;
        }
        long now = System.currentTimeMillis();
        long delay = (long) (1000.0 / cps.get());
        if (now - lastAttack < delay) {
            return;
        }
        lastAttack = now;
        if (rotate.isEnabled()) {
            face(player, target);
        }
        minecraft.gameMode.attack(player, target);
        player.resetAttackStrengthTicker();
    }

    private Entity findTarget(Minecraft minecraft, LocalPlayer player) {
        double max = range.get();
        double bestDistance = max * max;
        Entity best = null;
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (entity == player || !entity.isAlive() || entity.isSpectator()) {
                continue;
            }
            if (entity instanceof Player) {
                if (!players.isEnabled()) {
                    continue;
                }
            } else if (!(entity instanceof LivingEntity) || !mobs.isEnabled()) {
                continue;
            }
            double distance = player.distanceToSqr(entity);
            if (distance <= bestDistance) {
                bestDistance = distance;
                best = entity;
            }
        }
        return best;
    }

    private void face(LocalPlayer player, Entity target) {
        Vec3 eye = player.getEyePosition();
        Vec3 point = target.position().add(0.0, target.getBbHeight() / 2.0, 0.0);
        double dx = point.x - eye.x;
        double dy = point.y - eye.y;
        double dz = point.z - eye.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx))) - 90.0F;
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horizontal)));
        player.setYRot(yaw);
        player.setXRot(pitch);
    }
}
