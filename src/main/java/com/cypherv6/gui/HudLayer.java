package com.cypherv6.gui;

import com.cypherv6.CypherV6;
import com.cypherv6.module.Module;
import com.cypherv6.modules.client.Hud;
import com.cypherv6.modules.combat.Tracer;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraftforge.client.gui.overlay.ForgeLayer;

import java.util.List;

/**
 * Draws the CypherV6 watermark, the enabled-module array list, and 2D tracers.
 */
public class HudLayer implements ForgeLayer {

    private static final int WATERMARK_COLOUR = 0xFF9B59B6;
    private static final int MODULE_COLOUR = 0xFF55FF55;
    private static final int PADDING = 3;
    private static final int ROW = 10;

    @Override
    public void extract(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }

        int width = graphics.guiWidth();

        // Tracers are independent of the HUD module.
        Tracer.render(graphics, width, graphics.guiHeight());

        if (Hud.showWatermark()) {
            graphics.text(minecraft.font, "CypherV6", PADDING, PADDING, WATERMARK_COLOUR, true);
        }

        if (Hud.showArrayList()) {
            List<Module> enabled = CypherV6.MODULES.getEnabledSorted();
            int y = PADDING;
            for (Module module : enabled) {
                String name = module.getName();
                int textWidth = minecraft.font.width(name);
                int x = width - textWidth - PADDING;
                graphics.fill(x - 2, y - 1, width, y + 9, 0x90101018);
                graphics.text(minecraft.font, name, x, y, MODULE_COLOUR, true);
                y += ROW;
            }
        }
    }
}
