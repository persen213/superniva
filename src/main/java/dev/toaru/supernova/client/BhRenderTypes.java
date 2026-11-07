package dev.toaru.supernova.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;

/** Custom render types for Supernova visual effects. */
public final class BhRenderTypes extends RenderType {
    private BhRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode, int size, boolean crumbling, boolean sort,
                          Runnable setup, Runnable clear) {
        super(name, format, mode, size, crumbling, sort, setup, clear);
        throw new IllegalStateException("not meant to be constructed");
    }

    /** Flat colored geometry - dark core */
    public static final RenderType DARK = create("supernova_dark", DefaultVertexFormat.POSITION_COLOR, VertexFormat.Mode.QUADS, 8192, false, true,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.POSITION_COLOR_SHADER)
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .createCompositeState(false));

    /** Additive glow - rings, fire */
    public static final RenderType GLOW = RenderType.lightning();

    private static RenderType create(String name, VertexFormat format, VertexFormat.Mode mode, int size, boolean crumbling, boolean sort, RenderType.CompositeState state) {
        return new RenderType(name, format, mode, size, crumbling, sort, () -> {}, () -> {}) {
            @Override
            public RenderType.CompositeState state() { return state; }
        };
    }
}