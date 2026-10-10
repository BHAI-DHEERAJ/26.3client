package com.cypherv6.modules.client;

import com.cypherv6.CypherV6;
import com.cypherv6.module.Category;
import com.cypherv6.module.Module;
import com.cypherv6.setting.BooleanSetting;

/**
 * Controls the on-screen HUD: the enabled-module array list and the watermark.
 */
public class Hud extends Module {

    private final BooleanSetting arrayList = register(new BooleanSetting("ArrayList", true));
    private final BooleanSetting watermark = register(new BooleanSetting("Watermark", true));

    public Hud() {
        super("HUD", Category.CLIENT, "Shows the CypherV6 array list and watermark");
    }

    public static boolean showArrayList() {
        Hud hud = CypherV6.MODULES.get(Hud.class);
        return hud != null && hud.isEnabled() && hud.arrayList.isEnabled();
    }

    public static boolean showWatermark() {
        Hud hud = CypherV6.MODULES.get(Hud.class);
        return hud != null && hud.isEnabled() && hud.watermark.isEnabled();
    }
}
