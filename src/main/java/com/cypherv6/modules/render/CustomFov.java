package com.cypherv6.modules.render;

import com.cypherv6.CypherV6;
import com.cypherv6.module.Category;
import com.cypherv6.module.Module;
import com.cypherv6.setting.BooleanSetting;
import com.cypherv6.setting.NumberSetting;
import net.minecraftforge.client.event.ViewportEvent;

/**
 * Overrides the camera FOV with a custom value.
 */
public class CustomFov extends Module {

    private final BooleanSetting override = register(new BooleanSetting("Override", true));
    private final NumberSetting fov = register(new NumberSetting("FOV", 100.0, 30.0, 160.0, 1.0));

    public CustomFov() {
        super("CustomFOV", Category.RENDER, "Overrides the game field of view");
    }

    public static void onComputeFov(ViewportEvent.ComputeFov event) {
        CustomFov module = CypherV6.MODULES.get(CustomFov.class);
        if (module != null && module.isEnabled() && module.override.isEnabled()) {
            event.setFOV(module.fov.getFloat());
        }
    }
}
