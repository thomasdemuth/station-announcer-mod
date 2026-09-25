package com.stationannouncer.mtr;

import com.stationannouncer.ModContent;
import com.stationannouncer.StationAnnouncer;
import com.stationannouncer.mtraddon.GapFillerEngine;
import com.stationannouncer.mtraddon.GapFillerStore;
import com.stationannouncer.mtraddon.disruption.MtrSimulators;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;
import org.mtr.core.data.Platform;
import org.mtr.core.data.Position;
import org.mtr.core.data.Rail;
import org.mtr.core.data.RailMath;
import org.mtr.core.data.Station;
import org.mtr.core.simulation.Simulator;
import org.mtr.core.tool.Vector;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;

/**
 * Gap fillers, server side: registration, the platform link (nearest MTR
 * platform + automatic plate reach from its rail), the brush screen's packet,
 * and the {@link GapFillerStore} lifecycle. One file plus one call from
 * {@link MtrStationDecor#register()}, like {@link SubwayWalls}. Assets come
 * from {@code tools/gen_gap_filler_assets.py} — never hand-edit them.
 *
 * <p><b>Reach from the rail.</b> A car body is a straight chord between its
 * bogies, so on a curve its middle swings in toward the centre of the curve by
 * the sagitta L²/8R. A platform on the OUTSIDE of a curve therefore sees its
 * biggest gap at mid-car — exactly where the middle doors that forced gap
 * fillers on the IRT are. The automatic reach is the distance from the edge
 * face to the car side at the rail ({@link #CAR_HALF_WIDTH}) plus that bulge
 * for a {@link #CAR_LENGTH} car on outside curves, plus a little press so the
 * plate lands against the car rather than short of it. The planned curved
 * platform creator (GAP_FILLER_PLAN.md) will measure its edge with the same
 * {@link #clearance} so the two agree.</p>
 */
public final class GapFillers {
    /** Half a car body width in blocks (MTR's 3-block-wide stock). */
    public static final double CAR_HALF_WIDTH = 1.5;
    /** Car length the chord allowance is sized for (an IRT car is ~16 m; MTR cars run 20+). */
    public static final double CAR_LENGTH = 20.0;
    /** Plates land this far into the car side instead of stopping short (px). */
    public static final float PRESS_PX = 2f;
    /** A platform rail further than this from the edge face is not ours. */
    private static final double MAX_LINK_DISTANCE = 4.0;

    public static final GapFillerBlock GAP_FILLER = new GapFillerBlock(settings(), GapFillerBlock.Style.UNION);
    public static final GapFillerBlock GAP_FILLER_LOOP = new GapFillerBlock(settings(), GapFillerBlock.Style.LOOP);

    public static final BlockEntityType<GapFillerBlockEntity> GAP_FILLER_BLOCK_ENTITY =
            BlockEntityType.Builder.create(GapFillerBlockEntity::new, GAP_FILLER, GAP_FILLER_LOOP).build(null);

    public static final SoundEvent SOUND_HYDRAULIC = SoundEvent.of(StationAnnouncer.id("gap_filler_hydraulic"));
    public static final SoundEvent SOUND_ROLL = SoundEvent.of(StationAnnouncer.id("gap_filler_roll"));
    public static final SoundEvent SOUND_BANG = SoundEvent.of(StationAnnouncer.id("gap_filler_bang"));

    /** C2S: the brush screen (pos, action, reach, extend/retract/min dwell ms). */
    public static final Identifier UPDATE_GAP_FILLER_C2S = StationAnnouncer.id("update_gap_filler");
    public static final int ACTION_SAVE = 0;
    public static final int ACTION_RELINK = 1;
    public static final int ACTION_AUTO_REACH = 2;

    private static int saveCountdown;

    private GapFillers() {
    }

    private static AbstractBlock.Settings settings() {
        return AbstractBlock.Settings.create().strength(1.6f, 6.0f).sounds(BlockSoundGroup.STONE).nonOpaque();
    }

    public static void register() {
        registerBlock("gap_filler", GAP_FILLER);
        registerBlock("gap_filler_loop", GAP_FILLER_LOOP);
        Registry.register(Registries.BLOCK_ENTITY_TYPE, StationAnnouncer.id("gap_filler"), GAP_FILLER_BLOCK_ENTITY);
        for (SoundEvent sound : new SoundEvent[]{SOUND_HYDRAULIC, SOUND_ROLL, SOUND_BANG}) {
            Registry.register(Registries.SOUND_EVENT, sound.getId(), sound);
        }

        ServerPlayNetworking.registerGlobalReceiver(UPDATE_GAP_FILLER_C2S, (server, player, handler, buf, responseSender) -> {
            BlockPos pos = buf.readBlockPos();
            int action = buf.readByte();
            int reach = buf.readByte();
            int extendMs = buf.readVarInt();
            int retractMs = buf.readVarInt();
            int minDwellMs = buf.readVarInt();
            server.execute(() -> handleUpdate(player, pos, action, reach, extendMs, retractMs, minDwellMs));
        });

        ServerLifecycleEvents.SERVER_STARTED.register(GapFillerStore::load);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> GapFillerStore.unload());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> GapFillerEngine.clearRuntimeState());
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (--saveCountdown <= 0) {
                saveCountdown = 100;
                GapFillerStore.saveIfDirty();
                GapFillerEngine.prune();
            }
        });
    }

    private static void registerBlock(String name, Block block) {
        Registry.register(Registries.BLOCK, StationAnnouncer.id(name), block);
        BlockItem item = new BlockItem(block, new Item.Settings());
        Registry.register(Registries.ITEM, StationAnnouncer.id(name), item);
        ModContent.OPERATIONS_ENTRIES.add(item);
    }

    private static void handleUpdate(ServerPlayerEntity player, BlockPos pos, int action, int reach,
                                     int extendMs, int retractMs, int minDwellMs) {
        ServerWorld world = player.getServerWorld();
        if (player.squaredDistanceTo(Vec3d.ofCenter(pos)) > 64.0 * 64.0 || !world.canPlayerModifyAt(player, pos)
                || !(world.getBlockEntity(pos) instanceof GapFillerBlockEntity filler)) {
            return;
        }
        BlockState state = world.getBlockState(pos);
        switch (action) {
            case ACTION_SAVE -> {
                int clamped = Math.max(1, Math.min(12, reach));
                if (state.get(GapFillerBlock.REACH) != clamped) {
                    world.setBlockState(pos, state.with(GapFillerBlock.REACH, clamped), Block.NOTIFY_ALL);
                    filler.setReachAuto(false);
                }
                if (filler.getPlatformId() != 0) {
                    GapFillerStore.setSettings(filler.getPlatformId(),
                            new GapFillerStore.Settings(extendMs, retractMs, minDwellMs));
                }
            }
            case ACTION_RELINK -> {
                filler.requestRelink();
                link(world, pos, filler.isReachAuto(), player);
            }
            case ACTION_AUTO_REACH -> {
                filler.setReachAuto(true);
                link(world, pos, true, player);
            }
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ link

    /** What a rail lookup found for one filler. */
    private record Found(long platformId, String label, int reach) {
    }

    /**
     * Server thread: find the platform whose rail runs past this filler's
     * edge, then (on the server thread again) link the block entity and, when
     * {@code autoReach}, set the plate reach from the measured gap. The rail
     * data is only read inside {@code simulator.run} (TSC thread), like the
     * bridge creator does; only plain values cross back.
     */
    public static void link(ServerWorld world, BlockPos pos, boolean autoReach, @Nullable ServerPlayerEntity feedback) {
        Simulator simulator = simulatorFor(world);
        BlockState state = world.getBlockState(pos);
        if (simulator == null || !(state.getBlock() instanceof GapFillerBlock)) {
            return;
        }
        Direction side = state.get(GapFillerBlock.TRACK_SIDE);
        double faceX = pos.getX() + 0.5 + side.getOffsetX() * 0.5;
        double faceZ = pos.getZ() + 0.5 + side.getOffsetZ() * 0.5;
        int y = pos.getY();
        MinecraftServer server = world.getServer();
        simulator.run(() -> {
            Found found = null;
            try {
                found = nearestPlatform(simulator, faceX, y, faceZ, side);
            } catch (Throwable t) {
                StationAnnouncer.LOGGER.warn("Gap filler: platform lookup failed", t);
            }
            Found result = found;
            server.execute(() -> {
                BlockState now = world.getBlockState(pos);
                if (!(world.getBlockEntity(pos) instanceof GapFillerBlockEntity filler) || !(now.getBlock() instanceof GapFillerBlock)) {
                    return;
                }
                if (result == null) {
                    if (feedback != null) {
                        feedback.sendMessage(Text.translatable("msg.station_announcer.gap_filler.no_platform"), true);
                    }
                    return;
                }
                filler.applyLink(result.platformId(), result.label());
                if (autoReach && now.get(GapFillerBlock.REACH) != result.reach()) {
                    world.setBlockState(pos, now.with(GapFillerBlock.REACH, result.reach()), Block.NOTIFY_ALL);
                }
                if (feedback != null) {
                    feedback.sendMessage(Text.translatable("msg.station_announcer.gap_filler.linked",
                            result.label(), result.reach() * 2), true);
                }
            });
        });
    }

    /** TSC thread. */
    @Nullable
    private static Found nearestPlatform(Simulator simulator, double faceX, int y, double faceZ, Direction side) {
        Platform best = null;
        Clearance bestClearance = null;
        for (Platform platform : simulator.platforms) {
            Rail rail = railOf(simulator, platform);
            if (rail == null) {
                continue;
            }
            RailMath railMath = rail.railMath;
            if (railMath.maxX < faceX - 8 || railMath.minX > faceX + 8 || railMath.maxZ < faceZ - 8
                    || railMath.minZ > faceZ + 8 || railMath.maxY < y - 4 || railMath.minY > y + 4) {
                continue;
            }
            Clearance clearance = clearance(railMath, faceX, y, faceZ, side);
            if (clearance != null && (bestClearance == null || clearance.distance() < bestClearance.distance())) {
                best = platform;
                bestClearance = clearance;
            }
        }
        if (best == null || bestClearance.distance() > MAX_LINK_DISTANCE) {
            return null;
        }
        double gapPx = (bestClearance.distance() - CAR_HALF_WIDTH + bestClearance.chordBulge()) * 16 + PRESS_PX;
        int reach = (int) Math.ceil(Math.max(2, Math.min(24, gapPx)) / 2);
        return new Found(best.getId(), label(best), Math.max(1, Math.min(12, reach)));
    }

    /**
     * How far a rail's centreline is from an edge face, measured on the track
     * side only, and the mid-car chord bulge away from the face when the face
     * is on the outside of a curve. Shared with the planned curved platform
     * creator. Rail positions are world coordinates (RailMath adds the +0.5).
     */
    public record Clearance(double distance, double chordBulge) {
    }

    @Nullable
    public static Clearance clearance(RailMath railMath, double faceX, int y, double faceZ, Direction side) {
        double length = railMath.getLength();
        double bestDistance = Double.MAX_VALUE;
        double bestT = -1;
        for (double t = 0; t <= length; t += 0.25) {
            Vector v = railMath.getPosition(t, false);
            if (Math.abs(v.y - y) > 3) {
                continue;
            }
            double dx = v.x - faceX;
            double dz = v.z - faceZ;
            if (dx * side.getOffsetX() + dz * side.getOffsetZ() <= 0) {
                continue; // behind the edge: this rail is on the platform side, not the track side
            }
            double distance = Math.sqrt(dx * dx + dz * dz);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestT = t;
            }
        }
        if (bestT < 0) {
            return null;
        }
        // Local radius of curvature from three samples 3 blocks apart.
        Vector a = railMath.getPosition(Math.max(0, bestT - 3), false);
        Vector b = railMath.getPosition(bestT, false);
        Vector c = railMath.getPosition(Math.min(length, bestT + 3), false);
        double bulge = 0;
        double cross = (b.x - a.x) * (c.z - a.z) - (b.z - a.z) * (c.x - a.x);
        if (Math.abs(cross) > 1e-6) {
            double ab = Math.hypot(b.x - a.x, b.z - a.z);
            double bc = Math.hypot(c.x - b.x, c.z - b.z);
            double ca = Math.hypot(a.x - c.x, a.z - c.z);
            double radius = ab * bc * ca / (2 * Math.abs(cross));
            // Centre of curvature is on the concave side: the side of a→c the midpoint b bulges away from.
            double mx = (a.x + c.x) / 2 - b.x;
            double mz = (a.z + c.z) / 2 - b.z;
            double faceSide = (faceX - b.x) * mx + (faceZ - b.z) * mz;
            if (faceSide < 0 && radius > CAR_LENGTH / 2) {
                bulge = Math.min(1.5, CAR_LENGTH * CAR_LENGTH / (8 * radius)); // outside of the curve
            }
        }
        return new Clearance(bestDistance, bulge);
    }

    @Nullable
    private static Rail railOf(Simulator simulator, Platform platform) {
        Position one = platform.getRandomPosition();
        Position two = platform.getOtherPosition(one);
        var fromOne = simulator.positionsToRail.get(one);
        return fromOne == null ? null : fromOne.get(two);
    }

    private static String label(Platform platform) {
        Station station = platform.area;
        String stationName = station == null ? "" : firstLang(station.getName());
        String platformName = firstLang(platform.getName());
        if (stationName.isEmpty()) {
            return platformName.isEmpty() ? "Platform" : "Platform " + platformName;
        }
        return platformName.isEmpty() ? stationName : stationName + " · " + platformName;
    }

    private static String firstLang(String name) {
        if (name == null) {
            return "";
        }
        int bar = name.indexOf('|');
        return (bar < 0 ? name : name.substring(0, bar)).trim();
    }

    @Nullable
    private static Simulator simulatorFor(ServerWorld world) {
        ObjectImmutableList<Simulator> simulators = MtrSimulators.get();
        if (simulators == null) {
            return null;
        }
        String id;
        try {
            id = org.mtr.mod.Init.getWorldId(new org.mtr.mapping.holder.World(world));
        } catch (Throwable t) {
            return null;
        }
        for (Simulator simulator : simulators) {
            if (simulator.dimension.equals(id)) {
                return simulator;
            }
        }
        return null;
    }
}
