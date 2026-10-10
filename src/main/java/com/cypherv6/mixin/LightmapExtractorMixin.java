package com.cypherv6.mixin;

import com.cypherv6.modules.render.Fullbright;
import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import net.minecraft.client.renderer.state.LightmapRenderState;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Real fullbright: after the lightmap state is extracted we force full white
 * "night vision" light plus maximum brightness, so the whole world renders lit.
 */
@Mixin(LightmapRenderStateExtractor.class)
public abstract class LightmapExtractorMixin {

    @Inject(method = "extract", at = @At("TAIL"), remap = false)
    private void cypherv6$fullbright(LightmapRenderState renderState, float partialTicks, CallbackInfo ci) {
        if (Fullbright.active) {
            renderState.nightVisionEffectIntensity = 1.0F;
            renderState.nightVisionColor = new Vector3f(1.0F, 1.0F, 1.0F);
            renderState.brightness = 1.0F;
            renderState.darknessEffectScale = 0.0F;
        }
    }
}
