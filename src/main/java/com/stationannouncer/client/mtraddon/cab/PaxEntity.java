package com.stationannouncer.client.mtraddon.cab;

import com.mojang.authlib.GameProfile;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.network.OtherClientPlayerEntity;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.scoreboard.AbstractTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.Team;
import org.jetbrains.annotations.Nullable;

/**
 * One cosmetic passenger of {@link CabPassengers}: a client-only player entity with a random
 * offline profile (the UUID picks one of vanilla's default skins), added to the
 * {@code ClientWorld} under a negative entity id the server never hands out. Nothing about it
 * ever reaches the server:
 *
 * <ul>
 *   <li>{@link #canHit} is false, so the crosshair raycast skips it — the player can never
 *       attack or use it, which is the only way an entity id would end up in a packet;</li>
 *   <li>it is never in the tab list (that list comes from the server's player-info packets),
 *       so the skin falls back to {@code DefaultSkinHelper} by UUID;</li>
 *   <li>a private, never-registered team hides its name tag (vanilla's {@code hasLabel} honours
 *       the team's NEVER rule) and turns collision off; it does not push or get pushed
 *       ({@link #tickCramming} is a no-op, {@link #isPushable} false).</li>
 * </ul>
 *
 * <p>Movement is fed exactly like a remote player's: {@code updateTrackedPositionAndAngles}
 * from {@link CabPassengers}' 20 Hz tick, which vanilla interpolates; the walking animation
 * comes from {@code OtherClientPlayerEntity.tick → updateLimbs}, i.e. from the position change.</p>
 */
@Environment(EnvType.CLIENT)
final class PaxEntity extends OtherClientPlayerEntity {
    private static final Team TEAM = createTeam();

    PaxEntity(ClientWorld world, GameProfile profile) {
        super(world, profile);
        // Show the skin's outer layers (hat, jacket, sleeves, trousers) — not the cape bit.
        getDataTracker().set(PLAYER_MODEL_PARTS, (byte) 0x7E);
    }

    private static Team createTeam() {
        Team team = new Team(new Scoreboard(), "station_announcer_pax");
        team.setNameTagVisibilityRule(AbstractTeam.VisibilityRule.NEVER);
        team.setCollisionRule(AbstractTeam.CollisionRule.NEVER);
        return team;
    }

    @Override
    public Team getScoreboardTeam() {
        return TEAM;
    }

    @Override
    public boolean canHit() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean isCollidable() {
        return false;
    }

    @Override
    protected void tickCramming() {
        // Never shoves the player (or another passenger).
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean isSpectator() {
        return false;
    }

    @Override
    public boolean isCreative() {
        return false;
    }

    @Nullable
    @Override
    protected PlayerListEntry getPlayerListEntry() {
        return null; // never in the tab list; skips the per-call lookup
    }
}
