package dev.toaru.supernova.client;

import dev.toaru.supernova.Cfg;
import dev.toaru.supernova.SupernovaMod;
import dev.toaru.supernova.client.ClientFx.Effect;
import dev.toaru.supernova.net.FxType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

/**
 * Supernova rendering - expanding rings of fire and force:
 * - Formation: growing star core with pulsing glow
 * - Explosion: multiple expanding rings (fire + force)
 * - Each ring: fire outer edge, force inner edge
 */
@Mod.EventBusSubscriber(modid = SupernovaMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class FxRenderer {
    private FxRenderer() { }

    private static final double MAX_DISTANCE = 220.0;

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || ClientFx.ACTIVE.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        float partial = mc.isPaused() ? 0f : event.getPartialTick();
        long now = mc.level.getGameTime();
        Vec3 cam = event.getCamera().getPosition();
        Matrix4f pose = event.getPoseStack().last().pose();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();

        // Pass 1: Dark geometry (star core)
        Geo dark = new Geo(buffers.getBuffer(BhRenderTypes.DARK), pose, cam);
        for (Effect fx : ClientFx.ACTIVE.values()) {
            if (fx.pos.distanceTo(cam) <= MAX_DISTANCE) drawDark(fx, fx.age(now, partial), dark, cam);
        }
        buffers.endBatch(BhRenderTypes.DARK);

        // Pass 2: Glow geometry (rings, fire, force)
        Geo glow = new Geo(buffers.getBuffer(BhRenderTypes.GLOW), pose, cam);
        for (Effect fx : ClientFx.ACTIVE.values()) {
            if (fx.pos.distanceTo(cam) <= MAX_DISTANCE) drawGlow(fx, fx.age(now, partial), glow, cam);
        }
        buffers.endBatch(BhRenderTypes.GLOW);
    }

    private static double clamp01(double v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }
    private static double smoothstep(double t) { return t * t * (3.0 - 2.0 * t); }
    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }

    private static int segments(Effect fx, Vec3 cam) {
        double dist = fx.pos.distanceTo(cam);
        return dist < 50 ? 56 : (dist < 120 ? 36 : 24);
    }

    /** Current visual radius based on phase. */
    private static double visualRadius(Effect fx, double age) {
        double form = clamp01(age / Math.max(1.0, fx.aux.y));
        double ease = smoothstep(form);
        double total = fx.duration;
        double activeStart = fx.aux.y;
        double activeEnd = total;

        if (age < activeStart) {
            return fx.aux.x * ease;
        } else if (age < total) {
            double progress = clamp01((age - activeStart) / Math.max(1, total - activeStart));
            return fx.aux.x * (1.0 + progress * 0.5);
        }
        return 0;
    }

    private static void drawDark(Effect fx, double age, Geo g, Vec3 cam) {
        int seg = segments(fx, cam);
        Vec3 c = fx.pos;
        double r = visualRadius(fx, age);

        if (r < 0.02) return;

        switch (fx.type) {
            case SUPERNOVA -> {
                // Star core during formation
                if (age < fx.aux.y) {
                    double coreR = r * 0.3;
                    g.color(1.0f, 0.9f, 0.5f, (float) (0.8 * smoothstep(age / fx.aux.y))).sphere(c, coreR, 10, seg / 2);
                    g.color(1.0f, 0.7f, 0.3f, 0.4f).sphere(c, coreR * 1.5, 8, seg / 2);
                }
            }
            case SUPERNOVA_RING -> {
                // Rings don't have dark geometry
            }
        }
    }

    private static void drawGlow(Effect fx, double age, Geo g, Vec3 cam) {
        int seg = segments(fx, cam);
        Vec3 c = fx.pos;

        switch (fx.type) {
            case SUPERNOVA -> supernovaGlow(fx, age, g, cam, seg, c);
            case SUPERNOVA_RING -> ringGlow(fx, age, g, cam, seg, c);
        }
    }

    private static void supernovaGlow(Effect fx, double age, Geo g, Vec3 cam, int seg, Vec3 c) {
        double form = clamp01(age / Math.max(1.0, fx.aux.y));
        double ease = smoothstep(form);
        double activeStart = fx.aux.y;

        if (age < activeStart) {
            // Formation: growing star with corona
            double coreR = fx.aux.x * 0.3 * ease;
            g.color(1.0f, 0.95f, 0.8f, (float) (0.9 * ease)).sphere(c, coreR, 10, seg / 2);
            g.color(1.0f, 0.7f, 0.2f, (float) (0.6 * ease)).sphere(c, coreR * 1.8, 8, seg / 2);
            g.color(1.0f, 0.5f, 0.1f, (float) (0.3 * ease)).sphere(c, coreR * 2.5, 8, seg / 2);

            // Pulsing corona rings
            for (int i = 0; i < 3; i++) {
                double ringPhase = age * 0.05 + i * 2.0;
                double ringR = coreR * (2.0 + i * 0.8);
                double pulse = Math.sin(ringPhase) * 0.15 + 1.0;
                g.color(1.0f, 0.6f, 0.2f, (float) (0.3 * ease * pulse * (1.0 - i * 0.2)))
                        .ring(c, new Vec3(0, 1, 0), ringR * pulse, coreR * 0.1, seg, ringPhase, false);
            }
            return;
        }

        // Explosion phase handled by SUPERNOVA_RING effects
    }

    private static void ringGlow(Effect fx, double age, Geo g, Vec3 cam, int seg, Vec3 c) {
        double t = clamp01(age / Math.max(1, fx.duration));
        double maxR = fx.aux.x;
        int ringIndex = (int) fx.aux.z;
        double totalRings = Cfg.RING_COUNT.get();

        // Ring radius grows from 0 to maxR over the ring's lifetime
        double ringProgress = t;
        double ringR = maxR * ringProgress;
        double fade = 1.0 - ringProgress;

        if (ringR < 0.1) return;

        // Outer fire ring
        g.color(1.0f, 0.7f, 0.1f, (float) (0.8 * fade)).ring(c, new Vec3(0, 1, 0), ringR, maxR * 0.05, seg, 0, false);
        g.color(1.0f, 0.4f, 0.0f, (float) (0.6 * fade)).ring(c, new Vec3(0, 1, 0), ringR, maxR * 0.03, seg, 0, false);

        // Inner force ring (blue-white)
        g.color(0.6f, 0.8f, 1.0f, (float) (0.5 * fade)).ring(c, new Vec3(0, 1, 0), ringR * 0.85, maxR * 0.04, seg, 0, false);
        g.color(0.3f, 0.7f, 1.0f, (float) (0.4 * fade)).ring(c, new Vec3(1, 0, 0), ringR * 0.85, maxR * 0.03, seg, 0, false);
        g.color(0.3f, 0.7f, 1.0f, (float) (0.4 * fade)).ring(c, new Vec3(0, 0, 1), ringR * 0.85, maxR * 0.03, seg, 0, false);

        // Fire particles - simulated with small spheres along ring
        if (ringProgress > 0.2 && ringProgress < 0.8) {
            for (int i = 0; i < 8; i++) {
                double ang = i * Math.PI * 2.0 / 8 + age * 0.1;
                Vec3 sparkPos = c.add(new Vec3(Math.cos(ang) * ringR, Math.sin((age + i) * 0.2) * maxR * 0.1, Math.sin(ang) * ringR));
                g.color(1.0f, 0.8f, 0.2f, (float) (0.7 * fade)).sphere(sparkPos, maxR * 0.02, 4, 6);
            }
        }

        // Expanding shockwave shell
        if (ringIndex == Cfg.RING_COUNT.get().intValue() - 1) {
            double shellR = maxR * ringProgress * 1.1;
            g.color(0.5f, 0.7f, 1.0f, (float) (0.15 * fade)).sphere(c, shellR, 12, seg / 2);
        }
    }
}