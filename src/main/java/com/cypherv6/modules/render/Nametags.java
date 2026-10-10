package com.cypherv6.modules.render;

import com.cypherv6.CypherV6;
import com.cypherv6.module.Category;
import com.cypherv6.module.Module;
import com.cypherv6.setting.BooleanSetting;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.client.event.RenderNameTagEvent;

/**
 * Enhances player name tags by appending live health. The entity id is read off the
 * player's render state and looked up in the client level.
 */
public class Nametags extends Module {

    private final BooleanSetting health = register(new BooleanSetting("Health", true));
    private final BooleanSetting hideScore = register(new BooleanSetting("Hide Score", false));

    public Nametags() {
        super("Nametags", Category.RENDER, "Shows player health on name tags");
    }

    public static void onRenderNameTag(RenderNameTagEvent event) {
        Nametags module = CypherV6.MODULES.get(Nametags.class);
        if (module == null || !module.isEnabled()) {
            return;
        }
        if (!module.health.isEnabled()) {
            if (module.hideScore.isEnabled()) {
                event.setScoreContent(Component.empty());
            }
            return;
        }
        if (!(event.getState() instanceof AvatarRenderState avatar)) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        Entity entity = minecraft.level.getEntity(avatar.id);
        if (!(entity instanceof Player player)) {
            return;
        }
        float current = player.getHealth();
        float max = player.getMaxHealth();
        ChatFormatting colour = current > max * 0.66F ? ChatFormatting.GREEN
                : current > max * 0.33F ? ChatFormatting.YELLOW : ChatFormatting.RED;
        Component healthText = Component.literal(" " + String.format("%.1f", current) + "\u2764").withStyle(colour);
        Component scoreText = Component.literal(String.format("%.1f", current) + "/" + String.format("%.0f", max))
                .withStyle(ChatFormatting.GRAY);
        event.setContent(Component.empty().append(event.getContent()).append(healthText));
        event.setScoreContent(scoreText);
    }
}
