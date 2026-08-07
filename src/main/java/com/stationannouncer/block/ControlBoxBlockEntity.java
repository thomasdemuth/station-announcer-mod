package com.stationannouncer.block;

import com.stationannouncer.ModContent;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The "brains" of a station PA network. Carries the full announcer feature
 * set (message pool, random self-trigger, delay, tags, redstone) but emits no
 * sound itself: when triggered it broadcasts through every linked
 * {@link SpeakerBlockEntity}. Speakers in unloaded chunks are skipped (never
 * force-loaded); list entries whose loaded position no longer holds a speaker
 * are pruned at fire time.
 */
public class ControlBoxBlockEntity extends AbstractPaBlockEntity {
    public static final int MAX_AUTO_SECONDS = 600;
    public static final int MAX_SPEAKERS = 256;

    /** With several messages: pick randomly (true) or cycle in order (false). */
    private boolean randomOrder = true;
    /** Self-trigger at a random interval of autoMin..autoMax seconds; 0 = disabled. */
    private int autoMinSeconds = 0;
    private int autoMaxSeconds = 0;

    /** Next message to play when cycling in order. Persisted so the rotation survives reloads. */
    private int messageIndex = 0;

    /** Positions of linked speakers (insertion order, deduplicated). */
    private final Set<BlockPos> speakers = new LinkedHashSet<>();

    /** Positions of linked PIDS displays (insertion order, deduplicated). */
    private final Set<BlockPos> displays = new LinkedHashSet<>();

    /** Ticks until the next automatic self-trigger; < 0 means unscheduled. Not persisted. */
    private int autoTicks = -1;

    public ControlBoxBlockEntity(BlockPos pos, BlockState state) {
        super(ModContent.CONTROL_BOX_BLOCK_ENTITY, pos, state);
    }

    // ------------------------------------------------------------------ NBT

    @Override
    protected void writeNbt(NbtCompound nbt) {
        super.writeNbt(nbt);
        nbt.putBoolean("RandomOrder", randomOrder);
        nbt.putInt("AutoMinSeconds", autoMinSeconds);
        nbt.putInt("AutoMaxSeconds", autoMaxSeconds);
        nbt.putInt("MessageIndex", messageIndex);
        nbt.putLongArray("Speakers", speakers.stream().mapToLong(BlockPos::asLong).toArray());
        nbt.putLongArray("Displays", displays.stream().mapToLong(BlockPos::asLong).toArray());
    }

    @Override
    public void readNbt(NbtCompound nbt) {
        super.readNbt(nbt);
        setRandomOrder(!nbt.contains("RandomOrder") || nbt.getBoolean("RandomOrder"));
        setAutoMinSeconds(nbt.getInt("AutoMinSeconds"));
        setAutoMaxSeconds(nbt.getInt("AutoMaxSeconds"));
        messageIndex = Math.max(0, nbt.getInt("MessageIndex"));
        speakers.clear();
        for (long packed : nbt.getLongArray("Speakers")) {
            if (speakers.size() >= MAX_SPEAKERS) {
                break;
            }
            speakers.add(BlockPos.fromLong(packed));
        }
        displays.clear();
        for (long packed : nbt.getLongArray("Displays")) {
            if (displays.size() >= MAX_SPEAKERS) {
                break;
            }
            displays.add(BlockPos.fromLong(packed));
        }
    }

    // -------------------------------------------------------------- ticking

    public static void serverTick(World world, BlockPos pos, BlockState state, ControlBoxBlockEntity be) {
        tickPending(be);
        be.tickAutoTrigger(world);
    }

    /** Counts down to the next random self-trigger while auto mode is enabled. */
    private void tickAutoTrigger(World world) {
        if (autoMinSeconds <= 0 || text.isBlank()) {
            autoTicks = -1; // disabled (or nothing to say): forget any schedule
            return;
        }
        if (autoTicks < 0) {
            scheduleNextAuto(world); // just loaded or just enabled
        } else if (--autoTicks == 0) {
            trigger();
            scheduleNextAuto(world);
        }
    }

    private void scheduleNextAuto(World world) {
        int minTicks = autoMinSeconds * 20;
        int maxTicks = Math.max(autoMinSeconds, autoMaxSeconds) * 20;
        autoTicks = world.getRandom().nextBetween(minTicks, maxTicks);
    }

    // ------------------------------------------------------------- messages

    /** Separator between announcements in the pool text (since 1.3; was one-per-line before). */
    public static final String MESSAGE_SEPARATOR = "||";

    /** Compiled once: String.split would rebuild this pattern on every call. */
    private static final java.util.regex.Pattern SEPARATOR_PATTERN = java.util.regex.Pattern.compile("\\|\\|");

    /** Splits a pool text on "||" into trimmed, non-blank announcements. */
    public static String[] splitMessages(String text) {
        return Arrays.stream(SEPARATOR_PATTERN.split(text))
                .map(String::trim)
                .filter(message -> !message.isEmpty())
                .toArray(String[]::new);
    }

    /** The announcements in the pool. */
    private String[] messages() {
        return splitMessages(text);
    }

    @Override
    protected String pickMessage(ServerWorld world) {
        String[] messages = messages();
        if (messages.length == 0) {
            return null;
        }
        if (messages.length == 1) {
            return messages[0];
        }
        if (randomOrder) {
            return messages[world.getRandom().nextInt(messages.length)];
        }
        messageIndex %= messages.length;
        String message = messages[messageIndex];
        messageIndex = (messageIndex + 1) % messages.length;
        markDirty(); // keep the rotation position across reloads
        return message;
    }

    // ------------------------------------------------------------- speakers

    @Override
    protected List<SoundSource> collectSources(ServerWorld world) {
        List<SoundSource> sources = new ArrayList<>();
        boolean pruned = false;
        for (var iterator = speakers.iterator(); iterator.hasNext(); ) {
            BlockPos speakerPos = iterator.next();
            if (!world.isChunkLoaded(speakerPos)) {
                continue; // never force-load: unloaded speakers just stay silent
            }
            if (world.getBlockEntity(speakerPos) instanceof SpeakerBlockEntity speaker) {
                sources.add(new SoundSource(speakerPos, speaker.getVolume(), speaker.getRadius()));
            } else {
                iterator.remove(); // speaker is gone (broken while we were unloaded)
                pruned = true;
            }
        }
        if (pruned) {
            sync();
        }
        return sources;
    }

    /**
     * Pushes the fired message to every loaded linked display so the screens
     * show it live (hanging PIDS scroll it; wall/standing show the pool under
     * "Happening now"). Entries whose loaded position no longer holds a
     * display are pruned, same self-healing as the speaker list.
     */
    @Override
    protected void onFired(ServerWorld world, String message) {
        boolean pruned = false;
        for (var iterator = displays.iterator(); iterator.hasNext(); ) {
            BlockPos displayPos = iterator.next();
            if (!world.isChunkLoaded(displayPos)) {
                continue; // never force-load: unloaded displays just miss this one
            }
            if (world.getBlockEntity(displayPos) instanceof PaDisplay display) {
                display.showPaAnnouncement(message);
                world.getChunkManager().markForUpdate(displayPos);
            } else {
                iterator.remove(); // display is gone (broken while we were unloaded)
                pruned = true;
            }
        }
        if (pruned) {
            sync();
        }
    }

    // ------------------------------------------------------------- displays

    /** @return true if the display was newly added. */
    public boolean addDisplay(BlockPos displayPos) {
        if (displays.size() >= MAX_SPEAKERS && !displays.contains(displayPos)) {
            return false;
        }
        return displays.add(displayPos.toImmutable());
    }

    public boolean removeDisplay(BlockPos displayPos) {
        return displays.remove(displayPos);
    }

    public boolean hasDisplay(BlockPos displayPos) {
        return displays.contains(displayPos);
    }

    public List<BlockPos> getDisplays() {
        return List.copyOf(displays);
    }

    public int getDisplayCount() {
        return displays.size();
    }

    /** @return true if the speaker was newly added. */
    public boolean addSpeaker(BlockPos speakerPos) {
        if (speakers.size() >= MAX_SPEAKERS && !speakers.contains(speakerPos)) {
            return false;
        }
        return speakers.add(speakerPos.toImmutable());
    }

    public boolean removeSpeaker(BlockPos speakerPos) {
        return speakers.remove(speakerPos);
    }

    public boolean hasSpeaker(BlockPos speakerPos) {
        return speakers.contains(speakerPos);
    }

    /** Unlinks every speaker and display (clearing loaded back-references too). */
    public void unlinkAllSpeakers() {
        if (world instanceof ServerWorld serverWorld) {
            for (BlockPos speakerPos : List.copyOf(speakers)) {
                if (serverWorld.isChunkLoaded(speakerPos)
                        && serverWorld.getBlockEntity(speakerPos) instanceof SpeakerBlockEntity speaker
                        && pos.equals(speaker.getControlBoxPos())) {
                    speaker.clearControlBox();
                }
            }
            for (BlockPos displayPos : List.copyOf(displays)) {
                if (serverWorld.isChunkLoaded(displayPos)
                        && serverWorld.getBlockEntity(displayPos) instanceof PaDisplay display
                        && pos.equals(display.getPaControlBoxPos())) {
                    display.setPaControlBoxPos(null);
                    serverWorld.getChunkManager().markForUpdate(displayPos);
                }
            }
        }
        speakers.clear();
        displays.clear();
        sync();
    }

    public List<BlockPos> getSpeakers() {
        return List.copyOf(speakers);
    }

    public int getSpeakerCount() {
        return speakers.size();
    }

    // ------------------------------------------------------------ accessors

    public boolean isRandomOrder() {
        return randomOrder;
    }

    public void setRandomOrder(boolean randomOrder) {
        this.randomOrder = randomOrder;
    }

    public int getAutoMinSeconds() {
        return autoMinSeconds;
    }

    public void setAutoMinSeconds(int autoMinSeconds) {
        this.autoMinSeconds = MathHelper.clamp(autoMinSeconds, 0, MAX_AUTO_SECONDS);
    }

    public int getAutoMaxSeconds() {
        return autoMaxSeconds;
    }

    public void setAutoMaxSeconds(int autoMaxSeconds) {
        this.autoMaxSeconds = MathHelper.clamp(autoMaxSeconds, 0, MAX_AUTO_SECONDS);
    }

    /** Applies a full settings update (from the GUI packet), persists and syncs. */
    public void applySettings(String text, int delaySeconds, String tag,
                              boolean showChat, boolean playChime, String chimeSound,
                              boolean randomOrder, int autoMinSeconds, int autoMaxSeconds) {
        setText(text);
        setDelaySeconds(delaySeconds);
        setAnnouncerTag(tag);
        setShowChat(showChat);
        setPlayChime(playChime);
        setChimeSound(chimeSound);
        setRandomOrder(randomOrder);
        setAutoMinSeconds(autoMinSeconds);
        setAutoMaxSeconds(autoMaxSeconds);
        autoTicks = -1; // reschedule the auto trigger with the new interval
        sync();
    }
}
