package com.cypherv6.util;

import com.mojang.blaze3d.platform.InputConstants;

/**
 * Turns raw GLFW key codes into readable names ("R", "F5", "None").
 */
public final class KeyUtil {

    /** Sentinel value for "no key bound" (matches the default in {@code Module}). */
    public static final int NONE = -1;

    private KeyUtil() {
    }

    public static String name(int key) {
        if (key < 0) {
            return "None";
        }
        try {
            return InputConstants.Type.KEYBOARD.getOrCreate(key).getDisplayName().getString();
        } catch (Throwable throwable) {
            return "Key " + key;
        }
    }
}
