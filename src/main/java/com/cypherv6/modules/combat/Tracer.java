package com.cypherv6.modules.combat;

import com.cypherv6.CypherV6;
import com.cypherv6.module.Category;
import com.cypherv6.module.Module;
import com.cypherv6.setting.BooleanSetting;
import com.cypherv6.setting.NumberSetting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Draws 2D tracers (screen-space lines) from the bottom-centre of the screen
 * toward nearby entities. Drawn by {@code HudLayer}.
 */
public class Tracer extends Module {

    private static final int COLOUR = 0xFFFF5555;

    private final NumberSetting range = register(new NumberSetting("Range", 48.0, 4.0, 128.0, 1.0));
    private final BooleanSetting players = register(new BooleanSetting("Players Only", true));

    public Tracer() {
        super("Tracer", Category.COMBAT, "Lines pointing to nearby entities");
    }

    public static void render(GuiGraphicsExtractor graphics, int width, int height) {
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        Tracer module = CypherV6.MODULES.get(Tracer.class);
        if (player == null || minecraft.level == null || module == null || !module.isEnabled()) {
            return;
        }
        double range = module.range.get();
        double fovY = minecraft.options.fov().get();
        double fovX = fovY * ((double) width / Math.max(1, height));
        float yaw = player.getYRot();
        float pitch = player.getXRot();
        int centreX = width / 2;
        int centreY = height / 2;
        Vec3 eye = player.getEyePosition();

        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (entity == player || !entity.isAlive() || entity.isSpectator()) {
                continue;
            }
            if (module.players.isEnabled() && !(entity instanceof Player)) {
                continue;
            }
            Vec3 point = entity.position().add(0.0, entity.getBbHeight() / 2.0, 0.0);
            double dx = point.x - eye.x;
            double dy = point.y - eye.y;
            double dz = point.z - eye.z;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (distance < 0.1 || distance > range) {
                continue;
            }
            double entityYaw = Math.toDegrees(Math.atan2(dz, dx)) - 90.0;
            double entityPitch = -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            double deltaYaw = wrapDegrees(entityYaw - yaw);
            double deltaPitch = entityPitch - pitch;
            if (Math.abs(deltaYaw) > fovX / 2.0 || Math.abs(deltaPitch) > fovY / 2.0) {
                continue;
            }
            int screenX = (int) (centreX + (deltaYaw / (fovX / 2.0)) * centreX);
            int screenY = (int) (centreY - (deltaPitch / (fovY / 2.0)) * centreY);
            line(graphics, centreX, height, screenX, screenY, COLOUR);
        }
    }

    private static double wrapDegrees(double angle) {
        double wrapped = angle % 360.0;
        if (wrapped >= 180.0) {
            wrapped -= 360.0;
        }
        if (wrapped < -180.0) {
            wrapped += 360.0;
        }
        return wrapped;
    }

    private static void line(GuiGraphicsExtractor graphics, int x1, int y1, int x2, int y2, int colour) {
        int dx = Math.abs(x2 - x1);
        int dy = Math.abs(y2 - y1);
        int sx = x1 < x2 ? 1 : -1;
        int sy = y1 < y2 ? 1 : -1;
        int err = dx - dy;
        int steps = 0;
        while (steps++ < 2048) {
            graphics.fill(x1, y1, x1 + 1, y1 + 1, colour);
            if (x1 == x2 && y1 == y2) {
                break;
            }
            int e2 = 2 * err;
            if (e2 > -dy) {
                err -= dy;
                x1 += sx;
            }
            if (e2 < dx) {
                err += dx;
                y1 += sy;
            }
        }
    }
}
