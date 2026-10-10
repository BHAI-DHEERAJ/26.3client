package com.cypherv6.util;

import com.cypherv6.CypherV6;
import com.cypherv6.module.Module;
import com.cypherv6.modules.client.Notifications;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Sends CypherV6 chat messages the way cheat clients do, e.g.
 * {@code [CypherV6] Fullbright enabled}.
 */
public final class ChatUtil {

    private ChatUtil() {
    }

    /** Sends a message, respecting the Notifications module. */
    public static void send(String text) {
        Notifications notifications = CypherV6.MODULES.get(Notifications.class);
        if (notifications == null || !notifications.isEnabled() || !notifications.chatEnabled()) {
            return;
        }
        raw(text);
    }

    /** Always sends, ignoring the Notifications module (used for startup feedback). */
    public static void raw(String text) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gui == null || minecraft.gui.hud == null) {
            return;
        }
        Component component = Component.empty()
                .append(Component.literal("[").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal("CypherV6").withStyle(ChatFormatting.LIGHT_PURPLE))
                .append(Component.literal("] ").withStyle(ChatFormatting.DARK_GRAY))
                .append(Component.literal(text).withStyle(ChatFormatting.GRAY));
        try {
            minecraft.gui.hud.getChat().addClientSystemMessage(component);
        } catch (Throwable ignored) {
            // Chat may not be ready yet (e.g. during the very first tick).
        }
    }

    /** Prints "{module} enabled/disabled", gated by the Notifications module. */
    public static void moduleToggle(Module module, boolean enabled) {
        Notifications notifications = CypherV6.MODULES.get(Notifications.class);
        if (notifications == null || !notifications.isEnabled()
                || !notifications.chatEnabled() || !notifications.toggleMessages()) {
            return;
        }
        raw(module.getName() + (enabled ? " \u00a7aenabled" : " \u00a7cdisabled"));
    }
}
