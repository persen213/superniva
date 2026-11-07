package dev.toaru.supernova;

import dev.toaru.supernova.net.Fx;
import dev.toaru.supernova.net.FxType;
import io.github.pinchan4273.reacademycraft.api.ActivationOutcome;
import io.github.pinchan4273.reacademycraft.api.StopReason;
import io.github.pinchan4273.reacademycraft.api.mc.AbilityActor;
import io.github.pinchan4273.reacademycraft.api.mc.ActivationContext;
import io.github.pinchan4273.reacademycraft.api.mc.AcademySafety;
import io.github.pinchan4273.reacademycraft.api.mc.SkillBehavior;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Supernova (level 4, one activation; registered as TOGGLE so the timed rings run - pressing the key again cancels it):
 * - Formation (1s): star forms at aimed spot
 * - Explosion: multiple expanding rings of fire and force damage
 * - Each ring damages and knocks back enemies
 */
public final class Supernova implements SkillBehavior {
    private static final class State {
        Vec3 centre;
        long fxKey;
        final Set<UUID> affectedEntities = new HashSet<>();
        int currentRing = 0;
        int lastRingTick = -1;
    }

    private static final Map<UUID, State> STATES = new HashMap<>();

    private static int formationTicks() { return Cfg.ticks(Cfg.FORMATION); }
    private static int totalTicks() { return formationTicks() + Cfg.ticks(Cfg.RING_DELAY) * Cfg.RING_COUNT.get().intValue(); }

    @Override public ActivationOutcome start(ActivationContext c) {
        ServerPlayer p = c.naturalUser();
        if (p == null) return ActivationOutcome.REFUSED_INVALID;

        Vec3 centre = Targeting.point(p, 40).add(0, 4.0, 0);
        if (!c.pay(Cfg.CP.get().floatValue(), Cfg.OVERLOAD.get().floatValue())) return ActivationOutcome.REFUSED_RESOURCE;

        ServerLevel level = p.serverLevel();
        State s = new State();
        s.centre = centre;
        s.fxKey = Fx.make(level, centre, FxType.SUPERNOVA, totalTicks()).range(220.0)
                .aux(new Vec3(Cfg.RADIUS.get(), formationTicks(), 0)).send();
        STATES.put(p.getUUID(), s);
        level.playSound(null, centre.x, centre.y, centre.z, SoundEvents.WITHER_SPAWN, net.minecraft.sounds.SoundSource.PLAYERS, 2.0f, 0.5f);
        return ActivationOutcome.STARTED;
    }

    @Override public boolean tick(ActivationContext c) {
        ServerPlayer p = c.naturalUser();
        State s = p == null ? null : STATES.get(p.getUUID());
        int age = c.age();
        if (p == null || s == null || age >= totalTicks()) return false;

        ServerLevel level = p.serverLevel();
        AbilityActor actor = c.actor();
        Vec3 centre = s.centre;
        double radius = Cfg.RADIUS.get();
        int formTicks = formationTicks();

        // Formation phase
        if (age < formTicks) {
            if (age % 5 == 0) {
                level.playSound(null, centre.x, centre.y, centre.z, SoundEvents.BLAZE_AMBIENT, net.minecraft.sounds.SoundSource.PLAYERS, 1.5f, 0.8f + age * 0.02f);
            }
            return true;
        }

        // Explosion phase - emit rings
        int explosionAge = age - formTicks;
        int ringDelay = Cfg.ticks(Cfg.RING_DELAY);
        int ringCount = Cfg.RING_COUNT.get().intValue();
        int currentRing = explosionAge / ringDelay;

        if (currentRing >= ringCount) return false;

        if (explosionAge % ringDelay == 0 && currentRing != s.lastRingTick) {
            s.lastRingTick = currentRing;
            s.currentRing = currentRing;
            emitRing(c, level, actor, centre, radius, currentRing, ringCount);
        }

        return true;
    }

    private void emitRing(ActivationContext c, ServerLevel level, AbilityActor actor, Vec3 centre, double maxRadius, int ringIndex, int totalRings) {
        double ringRadius = maxRadius * (ringIndex + 1) / (double) totalRings;
        double dmg = Cfg.DAMAGE.get() * (1.0 - ringIndex * 0.1); // damage decreases with each ring
        double kb = Cfg.KNOCKBACK.get() * (1.0 - ringIndex * 0.15);
        double bossDmg = Cfg.BOSS_DMG.get();
        double bossKb = Cfg.BOSS_KB.get();

        int hits = 0;
        for (LivingEntity e : Targeting.livingWithin(level, centre, ringRadius + 2.0, null)) {
            if (!Targeting.hostile(c.actor(), e, SupernovaMod.SUPERNOVA)) continue;
            double d = Targeting.centre(e).distanceTo(centre);
            if (d > ringRadius + 2.0) continue;
            double falloff = 1.0 - 0.5 * Math.min(1.0, d / ringRadius);
            double bossMul = Targeting.bossLike(e) ? bossDmg : 1.0;
            if (!AcademySafety.attack(c.actor(), e, SupernovaMod.SUPERNOVA, (float) (dmg * falloff * bossMul))) continue;
            Vec3 away = Targeting.centre(e).subtract(centre);
            away = away.lengthSqr() < 1e-4 ? new Vec3(0, 1, 0) : away.normalize();
            double k = kb * falloff * (Targeting.bossLike(e) ? bossKb : Targeting.controlFactor(e));
            Targeting.setMotion(e, new Vec3(away.x * k, 0.5 + away.y * k * 0.5, away.z * k));
            hits++;
        }

        if (hits > 0) c.addProficiency(0.01f * Math.min(hits, 3));

        // Visual ring effect
        Fx.make(level, centre, FxType.SUPERNOVA_RING, 20).range(220.0)
                .aux(new Vec3(ringRadius, 0, ringIndex)).send();

        level.playSound(null, centre.x, centre.y, centre.z, SoundEvents.GENERIC_EXPLODE, net.minecraft.sounds.SoundSource.PLAYERS, 2.0f - ringIndex * 0.2f, 0.6f + ringIndex * 0.05f);
    }

    @Override public void stop(ActivationContext c, StopReason reason) {
        ServerPlayer p = c.naturalUser();
        State s = p == null ? null : STATES.remove(p.getUUID());
        if (p != null && s != null) {
            ServerLevel level = p.serverLevel();
            Fx.cancel(level, s.fxKey);

            if (reason == StopReason.FINISHED || reason == StopReason.SWITCHED_OFF) {
                // Final burst
                level.playSound(null, s.centre.x, s.centre.y, s.centre.z, SoundEvents.WITHER_DEATH, net.minecraft.sounds.SoundSource.PLAYERS, 3.0f, 0.4f);
            }
        }
        c.setCooldown(Cfg.ticks(Cfg.COOLDOWN));
    }

    public static void forget(UUID player) { STATES.remove(player); }
    public static void clearAll() { STATES.clear(); }
}