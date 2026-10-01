package com.stationannouncer.client.render;

import com.stationannouncer.ModContent;
import com.stationannouncer.block.AnnouncerBlockEntity;
import com.stationannouncer.block.ControlBoxBlockEntity;
import com.stationannouncer.block.PaDisplay;
import com.stationannouncer.block.SpeakerBlockEntity;
import com.stationannouncer.item.SpeakerLinkItem;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.ItemStack;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Shows how far PA sound carries, and the area-link box:
 * <ul>
 *   <li>A speaker's (or standalone announcer's) reach as a ring where its sound
 *       sphere meets the player's own height — the exact line past which this
 *       player would stop hearing it. Drawn for the speaker screen being edited
 *       (live with the slider), and while holding the Speaker Link for the
 *       speaker / announcer under the crosshair (bright) and the speakers of
 *       the selected or looked-at Control Box (dim).</li>
 *   <li>A half-picked area link: the box from the first corner to the block
 *       under the crosshair, with every speaker and display inside marked.</li>
 * </ul>
 * Opaque debug quads in the entity phase, like {@link LinkLineRenderer}
 * (translucent overlays outside the BER pipeline flickered).
 */
@Environment(EnvType.CLIENT)
public final class PaRangeRenderer {
    private static final int MAX_DIM_RINGS = 32;
    private static final float BAND = 0.1f;

    @Nullable
    private static BlockPos previewPos;
    private static int previewRadius;

    private static List<BlockPos> areaMembers = List.of();
    private static BlockBox areaScanned;
    private static long areaNextScan;

    private PaRangeRenderer() {
    }

    public static void register() {
        WorldRenderEvents.AFTER_ENTITIES.register(PaRangeRenderer::render);
    }

    /** The speaker screen's live preview (called every frame while it is open). */
    public static void setPreview(BlockPos pos, int radius) {
        previewPos = pos.toImmutable();
        previewRadius = radius;
    }

    public static void clearPreview() {
        previewPos = null;
    }

    private record Ring(BlockPos pos, int radius, boolean bright) {
    }

    private static void render(WorldRenderContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        ClientWorld world = context.world();
        MatrixStack matrices = context.matrixStack();
        VertexConsumerProvider consumers = context.consumers();
        if (player == null || world == null || matrices == null || consumers == null) {
            return;
        }
        List<Ring> rings = new ArrayList<>();
        BlockBox area = null;
        if (previewPos != null) {
            rings.add(new Ring(previewPos, previewRadius, true));
        } else {
            ItemStack link = player.getMainHandStack().isOf(ModContent.SPEAKER_LINK) ? player.getMainHandStack()
                    : player.getOffHandStack().isOf(ModContent.SPEAKER_LINK) ? player.getOffHandStack() : null;
            if (link == null) {
                return;
            }
            BlockPos looked = client.crosshairTarget instanceof BlockHitResult hit
                    && hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null;
            BlockPos boxPos = SpeakerLinkItem.selectedBox(link);
            if (looked != null) {
                BlockEntity be = world.getBlockEntity(looked);
                if (be instanceof SpeakerBlockEntity speaker) {
                    rings.add(new Ring(looked, speaker.getRadius(), true));
                } else if (be instanceof AnnouncerBlockEntity announcer) {
                    rings.add(new Ring(looked, announcer.getRadius(), true));
                } else if (be instanceof ControlBoxBlockEntity) {
                    boxPos = looked;
                }
            }
            if (boxPos != null && world.getBlockEntity(boxPos) instanceof ControlBoxBlockEntity box) {
                addBoxRings(world, player, box, rings);
            }
            BlockPos corner = SpeakerLinkItem.areaCorner(link);
            if (corner != null) {
                area = BlockBox.create(corner, looked != null ? looked : corner);
            }
        }
        if (rings.isEmpty() && area == null) {
            return;
        }

        float pulse = 0.85f + 0.15f * MathHelper.sin((world.getTime() % 24000 + context.tickDelta()) * 0.25f);
        Vec3d camera = context.camera().getPos();
        matrices.push();
        matrices.translate(-camera.x, -camera.y, -camera.z);
        Matrix4f matrix = matrices.peek().getPositionMatrix();
        VertexConsumer buffer = consumers.getBuffer(RenderLayer.getDebugQuads());
        double feet = player.getLerpedPos(context.tickDelta()).y + 0.02;
        for (Ring ring : rings) {
            drawRing(matrix, buffer, ring, feet, pulse);
        }
        if (area != null) {
            drawArea(matrix, buffer, world, area, pulse);
        }
        matrices.pop();
    }

    private static void addBoxRings(ClientWorld world, ClientPlayerEntity player, ControlBoxBlockEntity box, List<Ring> rings) {
        List<BlockPos> speakers = new ArrayList<>(box.getSpeakers());
        BlockPos me = player.getBlockPos();
        speakers.sort(Comparator.comparingDouble(pos -> pos.getSquaredDistance(me)));
        int added = 0;
        for (BlockPos pos : speakers) {
            if (added >= MAX_DIM_RINGS) {
                break;
            }
            if (world.getBlockEntity(pos) instanceof SpeakerBlockEntity speaker
                    && rings.stream().noneMatch(r -> r.pos().equals(pos))) {
                rings.add(new Ring(pos, speaker.getRadius(), false));
                added++;
            }
        }
    }

    // ------------------------------------------------------------------ rings

    private static void drawRing(Matrix4f matrix, VertexConsumer buffer, Ring ring, double y, float pulse) {
        double cx = ring.pos().getX() + 0.5;
        double cy = ring.pos().getY() + 0.5;
        double cz = ring.pos().getZ() + 0.5;
        double dy = y - cy;
        double r2 = (double) ring.radius() * ring.radius() - dy * dy;
        if (r2 <= 0.25) {
            return; // the sphere does not reach this height
        }
        double r = Math.sqrt(r2);
        int segments = MathHelper.clamp((int) (r * 3), 24, 192);
        float red = ring.bright() ? 0.25f * pulse : 0.12f;
        float green = ring.bright() ? 0.85f * pulse : 0.42f;
        float blue = ring.bright() ? 1.0f * pulse : 0.55f;
        float y0 = (float) y;
        float y1 = (float) (y + BAND);
        double prevX = cx + r;
        double prevZ = cz;
        for (int i = 1; i <= segments; i++) {
            double angle = Math.PI * 2 * i / segments;
            double x = cx + Math.cos(angle) * r;
            double z = cz + Math.sin(angle) * r;
            quad(matrix, buffer, prevX, y0, prevZ, x, y0, z, x, y1, z, prevX, y1, prevZ, red, green, blue);
            quad(matrix, buffer, prevX, y1, prevZ, x, y1, z, x, y0, z, prevX, y0, prevZ, red, green, blue);
            prevX = x;
            prevZ = z;
        }
    }

    // ------------------------------------------------------------------- area

    private static void drawArea(Matrix4f matrix, VertexConsumer buffer, ClientWorld world, BlockBox area, float pulse) {
        float red = 1.0f * pulse;
        float green = 0.7f * pulse;
        float blue = 0.15f;
        boxEdges(matrix, buffer, area.getMinX(), area.getMinY(), area.getMinZ(),
                area.getMaxX() + 1, area.getMaxY() + 1, area.getMaxZ() + 1, 0.04, red, green, blue);
        for (BlockPos pos : membersOf(world, area)) {
            boxEdges(matrix, buffer, pos.getX() + 0.15, pos.getY() + 0.15, pos.getZ() + 0.15,
                    pos.getX() + 0.85, pos.getY() + 0.85, pos.getZ() + 0.85, 0.025, red, green, blue);
        }
    }

    /** Speakers and displays inside the area, rescanned a few times a second. */
    private static List<BlockPos> membersOf(ClientWorld world, BlockBox area) {
        long now = System.currentTimeMillis();
        if (area.equals(areaScanned) && now < areaNextScan) {
            return areaMembers;
        }
        areaScanned = area;
        areaNextScan = now + 250;
        List<BlockPos> found = new ArrayList<>();
        int sideX = area.getBlockCountX();
        int sideY = area.getBlockCountY();
        int sideZ = area.getBlockCountZ();
        if (sideX <= SpeakerLinkItem.MAX_AREA_SIDE && sideY <= SpeakerLinkItem.MAX_AREA_SIDE
                && sideZ <= SpeakerLinkItem.MAX_AREA_SIDE) {
            for (int cx = area.getMinX() >> 4; cx <= area.getMaxX() >> 4; cx++) {
                for (int cz = area.getMinZ() >> 4; cz <= area.getMaxZ() >> 4; cz++) {
                    if (world.getChunk(cx, cz, ChunkStatus.FULL, false) instanceof WorldChunk chunk) {
                        for (BlockEntity be : chunk.getBlockEntities().values()) {
                            if ((be instanceof SpeakerBlockEntity || be instanceof PaDisplay) && area.contains(be.getPos())) {
                                found.add(be.getPos());
                            }
                        }
                    }
                }
            }
        }
        areaMembers = found;
        return found;
    }

    private static void boxEdges(Matrix4f m, VertexConsumer b, double x0, double y0, double z0,
                                 double x1, double y1, double z1, double t, float r, float g, float bl) {
        // four along x, four along y, four along z
        for (double y : new double[]{y0, y1}) {
            for (double z : new double[]{z0, z1}) {
                bar(m, b, x0, y, z, x1, y, z, t, r, g, bl);
            }
        }
        for (double x : new double[]{x0, x1}) {
            for (double z : new double[]{z0, z1}) {
                bar(m, b, x, y0, z, x, y1, z, t, r, g, bl);
            }
        }
        for (double x : new double[]{x0, x1}) {
            for (double y : new double[]{y0, y1}) {
                bar(m, b, x, y, z0, x, y, z1, t, r, g, bl);
            }
        }
    }

    /** An axis-aligned square bar (2 crossed double-sided ribbons). */
    private static void bar(Matrix4f m, VertexConsumer b, double ax, double ay, double az,
                            double bx, double by, double bz, double t, float r, float g, float bl) {
        boolean alongX = ax != bx;
        boolean alongY = ay != by;
        // two perpendicular offsets
        double[] s1 = alongY ? new double[]{t, 0, 0} : new double[]{0, t, 0};
        double[] s2 = alongX ? new double[]{0, 0, t} : alongY ? new double[]{0, 0, t} : new double[]{t, 0, 0};
        for (double[] s : new double[][]{s1, s2}) {
            quad(m, b, ax + s[0], ay + s[1], az + s[2], bx + s[0], by + s[1], bz + s[2],
                    bx - s[0], by - s[1], bz - s[2], ax - s[0], ay - s[1], az - s[2], r, g, bl);
            quad(m, b, ax - s[0], ay - s[1], az - s[2], bx - s[0], by - s[1], bz - s[2],
                    bx + s[0], by + s[1], bz + s[2], ax + s[0], ay + s[1], az + s[2], r, g, bl);
        }
    }

    private static void quad(Matrix4f m, VertexConsumer b,
                             double x1, double y1, double z1, double x2, double y2, double z2,
                             double x3, double y3, double z3, double x4, double y4, double z4,
                             float r, float g, float bl) {
        b.vertex(m, (float) x1, (float) y1, (float) z1).color(r, g, bl, 1.0f).next();
        b.vertex(m, (float) x2, (float) y2, (float) z2).color(r, g, bl, 1.0f).next();
        b.vertex(m, (float) x3, (float) y3, (float) z3).color(r, g, bl, 1.0f).next();
        b.vertex(m, (float) x4, (float) y4, (float) z4).color(r, g, bl, 1.0f).next();
    }
}
