package com.stationannouncer.client.mtr;

import com.stationannouncer.block.FacingDecorBlock;
import com.stationannouncer.client.mtraddon.PosterLayout;
import com.stationannouncer.mtr.MtaSignBlock;
import com.stationannouncer.mtr.StationDecorBlockEntity;
import com.stationannouncer.mtr.sign.SignFaces;
import com.stationannouncer.mtr.sign.SignSpec;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.RotationAxis;
import java.util.HashMap;
import java.util.Map;

/**
 * Paints {@code mta_sign} / {@code mta_sign_half} panels: resolves the merged
 * run the block belongs to, then draws the front (and, on hanging/standing
 * mounts, the back) face through {@link SignLayout} on a
 * {@link PosterLayout.WorldSurface} — the same layout the editor previews.
 *
 * <p>Runs: adjacent panels with the same block, facing and mount merge. Only
 * the run's first cell (no continuing neighbour on its LEFT / model -x side)
 * draws; the content comes from the first cell along the run whose block
 * entity carries a sign. Both are cached for half a second — the answer only
 * changes when somebody builds or edits.</p>
 *
 * <p>Canvas: 64 units per block across the whole run, {@code plateHeight * 4}
 * units tall, origin at the run's +x end (the viewer's left) and the plate
 * top, drawn {@link #STANDOFF} in front of the plate face.</p>
 */
@Environment(EnvType.CLIENT)
final class MtaSignPainter {
    private static final float UNIT = 1.0f / 64.0f;
    private static final float STANDOFF = 0.004f;
    static final int MAX_RUN = 8;

    private static final SignSpec HINT = new SignSpec(SignSpec.Style.BLACK, java.util.List.of(
            SignSpec.Row.centered(new SignSpec.Tile(SignSpec.TileType.TEXT, "Right-click to edit sign", "", 1,
                    java.util.List.of()))));

    /** run 0 = another cell draws this panel. */
    private record Run(int run, SignFaces faces) {
    }

    private static final Map<Long, Run> RUNS = new HashMap<>();
    private static final Map<Long, SignContext> CONTEXTS = new HashMap<>();
    private static final long RUN_TTL_MS = 500;
    private static long runExpiry;

    private MtaSignPainter() {
    }

    static void clear() {
        RUNS.clear();
        CONTEXTS.clear();
    }

    static SignContext context(BlockPos pos) {
        return CONTEXTS.computeIfAbsent(pos.asLong(), key -> new SignContext(pos.toImmutable()));
    }

    private static boolean joins(BlockState other, BlockState self) {
        return other.getBlock() == self.getBlock()
                && other.get(FacingDecorBlock.FACING) == self.get(FacingDecorBlock.FACING)
                && other.get(MtaSignBlock.MOUNT) == self.get(MtaSignBlock.MOUNT);
    }

    private static Run run(StationDecorBlockEntity entity, ClientWorld world) {
        long now = System.currentTimeMillis();
        if (now >= runExpiry) {
            RUNS.clear();
            runExpiry = now + RUN_TTL_MS;
        }
        BlockPos pos = entity.getPos();
        Run cached = RUNS.get(pos.asLong());
        if (cached != null) {
            return cached;
        }
        BlockState self = entity.getCachedState();
        Direction facing = self.get(FacingDecorBlock.FACING);
        Direction negDir = facing.rotateYCounterclockwise();
        Direction posDir = facing.rotateYClockwise();
        Run run;
        if (joins(world.getBlockState(pos.offset(negDir)), self)) {
            run = new Run(0, null);
        } else {
            int count = 1;
            SignFaces faces = nonEmpty(entity.getSign());
            while (count < MAX_RUN && joins(world.getBlockState(pos.offset(posDir, count)), self)) {
                if (faces == null && world.getBlockEntity(pos.offset(posDir, count)) instanceof StationDecorBlockEntity other) {
                    faces = nonEmpty(other.getSign());
                }
                count++;
            }
            run = new Run(count, faces);
        }
        RUNS.put(pos.asLong(), run);
        return run;
    }

    private static SignFaces nonEmpty(SignFaces faces) {
        return faces == null || faces.isEmpty() ? null : faces;
    }

    /** How many cells the panel this block belongs to spans (1 when it is alone or not the origin). */
    static int runOf(StationDecorBlockEntity entity) {
        ClientWorld world = MinecraftClient.getInstance().world;
        if (world == null || !(entity.getCachedState().getBlock() instanceof MtaSignBlock)) {
            return 1;
        }
        BlockState self = entity.getCachedState();
        Direction facing = self.get(FacingDecorBlock.FACING);
        BlockPos pos = entity.getPos();
        // walk to the run's origin first, then count along it
        int back = 0;
        while (back < MAX_RUN && joins(world.getBlockState(pos.offset(facing.rotateYCounterclockwise(), back + 1)), self)) {
            back++;
        }
        BlockPos origin = pos.offset(facing.rotateYCounterclockwise(), back);
        int count = 1;
        while (count < MAX_RUN && joins(world.getBlockState(origin.offset(facing.rotateYClockwise(), count)), self)) {
            count++;
        }
        return count;
    }

    // ---------------------------------------------------------- legacy signs

    /**
     * The sign a run of legacy sign blocks draws: the first segment along the
     * run with a saved sign wins; otherwise a sign is derived from the merged
     * legacy fields (custom name / routes: first non-empty; face switches: AND).
     */
    static SignFaces legacyFaces(ClientWorld world, BlockPos origin, Direction posDir, int run,
                                 com.stationannouncer.mtr.sign.LegacySigns.Kind kind) {
        String custom = "";
        boolean front = true;
        boolean back = true;
        java.util.List<String> frontRoutes = java.util.List.of();
        java.util.List<String> backRoutes = java.util.List.of();
        for (int i = 0; i < run; i++) {
            if (!(world.getBlockEntity(pos(origin, posDir, i)) instanceof StationDecorBlockEntity segment)) {
                continue;
            }
            SignFaces saved = nonEmpty(segment.getSign());
            if (saved != null) {
                return saved;
            }
            if (custom.isEmpty()) {
                custom = segment.getCustomName();
            }
            front &= segment.isSignFront();
            back &= segment.isSignBack();
            if (frontRoutes.isEmpty()) {
                frontRoutes = segment.getFrontRoutes();
            }
            if (backRoutes.isEmpty()) {
                backRoutes = segment.getBackRoutes();
            }
        }
        return com.stationannouncer.mtr.sign.LegacySigns.derive(kind, custom, front, back, frontRoutes, backRoutes);
    }

    private static BlockPos pos(BlockPos origin, Direction dir, int steps) {
        return steps == 0 ? origin : origin.offset(dir, steps);
    }

    /** Length of a run of same-family blocks through {@code pos} along {@code posDir}, counted from its origin. */
    static int legacyRun(ClientWorld world, BlockPos pos, Direction posDir,
                         java.util.function.Predicate<BlockState> family) {
        int back = 0;
        while (back < MAX_RUN && family.test(world.getBlockState(pos.offset(posDir.getOpposite(), back + 1)))) {
            back++;
        }
        BlockPos origin = pos.offset(posDir.getOpposite(), back);
        int count = 1;
        while (count < MAX_RUN && family.test(world.getBlockState(origin.offset(posDir, count)))) {
            count++;
        }
        return count;
    }

    /** Paints one face of a legacy sign panel whose matrices are already at the canvas origin. */
    static void paintFace(MatrixStack matrices, VertexConsumerProvider consumers, SignSpec spec,
                          float width, float height, BlockPos pos) {
        if (spec == null) {
            return;
        }
        PosterLayout.WorldSurface surface = new PosterLayout.WorldSurface(matrices, consumers, SignLayout.FONT);
        SignLayout.paint(surface, spec, width, height, context(pos));
    }

    static void paint(StationDecorBlockEntity entity, MatrixStack matrices, VertexConsumerProvider consumers) {
        ClientWorld world = MinecraftClient.getInstance().world;
        if (world == null || !(entity.getCachedState().getBlock() instanceof MtaSignBlock block)) {
            return;
        }
        Run run = run(entity, world);
        if (run.run() == 0) {
            return;
        }
        BlockState state = entity.getCachedState();
        Direction facing = state.get(FacingDecorBlock.FACING);
        SignFaces faces = run.faces();
        SignSpec front = faces == null ? HINT : faces.frontSpec();
        SignSpec back = faces == null ? null : faces.backSpec();
        boolean doubleSided = MtaSignBlock.doubleSided(state);

        float width = 64 * run.run();
        float height = block.plateHeight() * 4;
        float top = block.plateTop(state) / 16.0f;
        float frontZ = MtaSignBlock.plateFront(state) / 16.0f - 0.5f;
        float backZ = MtaSignBlock.plateBack(state) / 16.0f - 0.5f;
        float centerOffset = (run.run() - 1) / 2.0f;
        float halfRun = run.run() / 2.0f;
        SignContext ctx = context(entity.getPos());

        matrices.push();
        matrices.translate(0.5, 0.0, 0.5);
        matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180.0f - facing.asRotation()));
        for (int side = 0; side < 2; side++) {
            SignSpec spec = side == 0 ? front : back;
            if (spec == null || (side == 1 && !doubleSided)) {
                continue;
            }
            matrices.push();
            if (side == 1) {
                matrices.multiply(RotationAxis.POSITIVE_Y.rotationDegrees(180.0f));
                matrices.translate(-centerOffset + halfRun, top, -backZ - STANDOFF);
            } else {
                matrices.translate(centerOffset + halfRun, top, frontZ - STANDOFF);
            }
            matrices.scale(-UNIT, -UNIT, UNIT);
            PosterLayout.WorldSurface surface = new PosterLayout.WorldSurface(matrices, consumers, SignLayout.FONT);
            SignLayout.paint(surface, spec, width, height, ctx);
            matrices.pop();
        }
        matrices.pop();
    }
}
