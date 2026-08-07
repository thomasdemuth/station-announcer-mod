package com.stationannouncer.block;

import com.stationannouncer.ModContent;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import java.util.List;

/**
 * The standalone Station Announcer: one PA source that speaks from its own
 * position with its own volume and radius. Message pools and random
 * self-triggering live on the PA Control Box, not here (v1.2 moved them; any
 * leftover RandomOrder/AutoMinSeconds/... NBT from older worlds is simply
 * ignored on load).
 */
public class AnnouncerBlockEntity extends AbstractPaBlockEntity {
    public static final int MIN_RADIUS = 1;
    public static final int MAX_RADIUS = 128;

    private int volume = 100; // 0..100 (%)
    private int radius = 16;  // 1..128 blocks

    public AnnouncerBlockEntity(BlockPos pos, BlockState state) {
        super(ModContent.ANNOUNCER_BLOCK_ENTITY, pos, state);
    }

    @Override
    protected void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        nbt.putInt("Volume", volume);
        nbt.putInt("Radius", radius);
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        setVolume(nbt.getInt("Volume"));
        setRadius(nbt.getInt("Radius"));
    }

    public static void serverTick(World world, BlockPos pos, BlockState state, AnnouncerBlockEntity be) {
        tickPending(be);
    }

    @Override
    protected List<SoundSource> collectSources(ServerWorld world) {
        return List.of(new SoundSource(pos, volume, radius));
    }

    public int getVolume() {
        return volume;
    }

    public void setVolume(int volume) {
        this.volume = MathHelper.clamp(volume, 0, 100);
    }

    public int getRadius() {
        return radius;
    }

    public void setRadius(int radius) {
        this.radius = MathHelper.clamp(radius, MIN_RADIUS, MAX_RADIUS);
    }

    /** Applies a full settings update (from the GUI packet), persists and syncs. */
    public void applySettings(String text, int volume, int delaySeconds, int radius, String tag,
                              boolean showChat, boolean playChime, String chimeSound) {
        setText(text);
        setVolume(volume);
        setDelaySeconds(delaySeconds);
        setRadius(radius);
        setAnnouncerTag(tag);
        setShowChat(showChat);
        setPlayChime(playChime);
        setChimeSound(chimeSound);
        sync();
    }
}
