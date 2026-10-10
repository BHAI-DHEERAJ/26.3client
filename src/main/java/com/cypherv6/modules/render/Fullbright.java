package com.cypherv6.modules.render;

import com.cypherv6.module.Category;
import com.cypherv6.module.Module;

/**
 * Makes the world fully lit. The heavy lifting happens in
 * {@code LightmapExtractorMixin}, which forces the lightmap to full white light.
 */
public class Fullbright extends Module {

    /** Read by the lightmap mixin (render thread) while toggled on the client thread. */
    public static volatile boolean active;

    public Fullbright() {
        super("Fullbright", Category.RENDER, "Makes the world fully lit");
    }

    @Override
    protected void onEnable() {
        active = true;
    }

    @Override
    protected void onDisable() {
        active = false;
    }
}
