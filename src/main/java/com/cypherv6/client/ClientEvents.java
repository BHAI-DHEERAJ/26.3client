package com.cypherv6.client;

import com.cypherv6.CypherV6;
import com.cypherv6.gui.ClickGui;
import com.cypherv6.gui.HudLayer;
import com.cypherv6.module.Module;
import com.cypherv6.modules.render.CustomFov;
import com.cypherv6.modules.render.Nametags;
import com.cypherv6.util.ChatUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderNameTagEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.event.TickEvent;
import com.mojang.blaze3d.platform.InputConstants;

/**
 * Wires CypherV6 into the 26.3 Forge event bus and opens the click GUI on RIGHT SHIFT.
 */
public final class ClientEvents {

    private static final Identifier HUD_LAYER_ID = Identifier.fromNamespaceAndPath(CypherV6.MODID, "hud");

    private ClientEvents() {
    }

    public static void register() {
        Module.setToggleListener(ChatUtil::moduleToggle);
        TickEvent.ClientTickEvent.Post.BUS.addListener(event -> onClientTick());
        ViewportEvent.ComputeFov.BUS.addListener(CustomFov::onComputeFov);
        RenderNameTagEvent.BUS.addListener(Nametags::onRenderNameTag);
        AddGuiOverlayLayersEvent.BUS.addListener(ClientEvents::onAddOverlayLayers);
        InputEvent.Key.BUS.addListener(ClientEvents::onKey);
    }

    private static int joinTicks = -1;

    private static void onClientTick() {
        CypherV6.MODULES.onTick();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            joinTicks = -1;
            return;
        }
        if (joinTicks < 0) {
            joinTicks = 0;
        } else {
            joinTicks++;
        }
        if (joinTicks == 20) {
            ChatUtil.raw("loaded - press RIGHT SHIFT for the GUI");
        }
    }

    private static void onAddOverlayLayers(AddGuiOverlayLayersEvent event) {
        event.getLayeredDraw().add(HUD_LAYER_ID, new HudLayer());
    }

    private static void onKey(InputEvent.Key event) {
        if (event.getAction() != InputConstants.PRESS) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        // Only react in the game world, not while another screen (inventory, chat...) is open.
        if (minecraft.player == null || minecraft.gui.screen() != null) {
            return;
        }
        int key = event.getKey();
        if (key == InputConstants.KEY_RSHIFT) {
            minecraft.setScreenAndShow(new ClickGui());
            return;
        }
        // Toggle any module bound to this key.
        CypherV6.MODULES.onKey(key);
    }
}
