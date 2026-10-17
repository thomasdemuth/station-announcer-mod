package com.stationannouncer.pa;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * A PA source's train-timed announcements: which MTR platforms it listens to and
 * what it says around each train's stop there (before arrival, doors open, doors
 * closing, after departure), with a repeat count. Pure data — no MTR types —
 * stored on every PA source under the {@code Trains} NBT key and edited through
 * the {@code update_train_pa} packet. The MTR module ({@code mtr/PaTrains}) does
 * the listening.
 */
public final class TrainAnnouncements {
    public static final int MAX_ROWS = 8;
    public static final int MAX_PLATFORMS = 32;
    public static final int MAX_SECONDS = 300;
    public static final int MAX_REPEATS = 5;
    public static final int MIN_GAP = 5;
    public static final int MAX_GAP = 120;
    /** One row's text: the same cap as a single PA message. */
    public static final int MAX_TEXT = 512;

    /** The moment in a train's stop a row is timed from. */
    public enum Event {
        BEFORE_ARRIVAL("Before arrival", "Arriving", "before it arrives", true),
        DOORS_OPEN("Doors open", "Doors open", "after the doors open", false),
        DOORS_CLOSING("Doors closing", "Doors close", "before the doors close", true),
        AFTER_DEPARTURE("After departure", "Departed", "after it leaves", false);

        public final String label;
        /** Fits half a narrow pane (the editor's event buttons). */
        public final String shortLabel;
        public final String timing;
        /** Seconds count BACK from the event (it is predicted) rather than forward. */
        public final boolean before;

        Event(String label, String shortLabel, String timing, boolean before) {
            this.label = label;
            this.shortLabel = shortLabel;
            this.timing = timing;
            this.before = before;
        }

        public static Event byIndex(int index) {
            Event[] values = values();
            return values[MathHelper.clamp(index, 0, values.length - 1)];
        }
    }

    /** One timed announcement. */
    public record Row(Event event, int seconds, int repeats, int gapSeconds, boolean enabled, String text) {
        public Row {
            seconds = MathHelper.clamp(seconds, 0, MAX_SECONDS);
            repeats = MathHelper.clamp(repeats, 1, MAX_REPEATS);
            gapSeconds = MathHelper.clamp(gapSeconds, MIN_GAP, MAX_GAP);
            String clean = PaText.clean(text == null ? "" : text);
            text = clean.length() > MAX_TEXT ? clean.substring(0, MAX_TEXT) : clean;
        }

        public Row withEvent(Event value) {
            return new Row(value, seconds, repeats, gapSeconds, enabled, text);
        }

        public Row withSeconds(int value) {
            return new Row(event, value, repeats, gapSeconds, enabled, text);
        }

        public Row withRepeats(int value) {
            return new Row(event, seconds, value, gapSeconds, enabled, text);
        }

        public Row withGap(int value) {
            return new Row(event, seconds, repeats, value, enabled, text);
        }

        public Row withEnabled(boolean value) {
            return new Row(event, seconds, repeats, gapSeconds, value, text);
        }

        public Row withText(String value) {
            return new Row(event, seconds, repeats, gapSeconds, enabled, value);
        }

        /** "30 s before it arrives", "as the doors open", … */
        public String timingLabel() {
            if (seconds == 0) {
                return switch (event) {
                    case BEFORE_ARRIVAL -> "as it arrives";
                    case DOORS_OPEN -> "as the doors open";
                    case DOORS_CLOSING -> "as the doors close";
                    case AFTER_DEPARTURE -> "as it leaves";
                };
            }
            return seconds + " s " + event.timing;
        }

        /** "30 s before it arrives · 2×" — a card's one-line summary. */
        public String summary() {
            String timing = timingLabel();
            return Character.toUpperCase(timing.charAt(0)) + timing.substring(1) + (repeats > 1 ? " · " + repeats + "×" : "");
        }

        public String repeatLabel() {
            return repeats == 1 ? "once" : String.format(Locale.ROOT, "%d× every %d s", repeats, gapSeconds);
        }
    }

    /** true = the platforms near the source and its speakers; false = {@link #platforms} only. */
    public final boolean autoPlatforms;
    public final List<Long> platforms;
    public final List<Row> rows;

    public static final TrainAnnouncements EMPTY = new TrainAnnouncements(true, List.of(), List.of());

    public TrainAnnouncements(boolean autoPlatforms, List<Long> platforms, List<Row> rows) {
        this.autoPlatforms = autoPlatforms;
        Set<Long> unique = new LinkedHashSet<>();
        for (Long id : platforms) {
            if (id != null && id != 0 && unique.size() < MAX_PLATFORMS) {
                unique.add(id);
            }
        }
        this.platforms = List.copyOf(unique);
        this.rows = List.copyOf(rows.size() > MAX_ROWS ? rows.subList(0, MAX_ROWS) : rows);
    }

    /** Anything to listen for: at least one switched-on row with text. */
    public boolean active() {
        for (Row row : rows) {
            if (row.enabled() && !row.text().isBlank()) {
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------------------------- NBT

    public NbtCompound toNbt() {
        NbtCompound nbt = new NbtCompound();
        nbt.putBoolean("Auto", autoPlatforms);
        nbt.putLongArray("Platforms", platforms.stream().mapToLong(Long::longValue).toArray());
        NbtList list = new NbtList();
        for (Row row : rows) {
            NbtCompound r = new NbtCompound();
            r.putByte("Event", (byte) row.event().ordinal());
            r.putInt("Seconds", row.seconds());
            r.putInt("Repeats", row.repeats());
            r.putInt("Gap", row.gapSeconds());
            r.putBoolean("On", row.enabled());
            r.putString("Text", row.text());
            list.add(r);
        }
        nbt.put("Rows", list);
        return nbt;
    }

    public static TrainAnnouncements fromNbt(NbtCompound nbt) {
        if (nbt == null || nbt.isEmpty()) {
            return EMPTY;
        }
        List<Long> platforms = new ArrayList<>();
        for (long id : nbt.getLongArray("Platforms")) {
            platforms.add(id);
        }
        List<Row> rows = new ArrayList<>();
        NbtList list = nbt.getList("Rows", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < list.size() && rows.size() < MAX_ROWS; i++) {
            NbtCompound r = list.getCompound(i);
            rows.add(new Row(Event.byIndex(r.getByte("Event")), r.getInt("Seconds"),
                    r.contains("Repeats") ? r.getInt("Repeats") : 1,
                    r.contains("Gap") ? r.getInt("Gap") : 20,
                    !r.contains("On") || r.getBoolean("On"), r.getString("Text")));
        }
        return new TrainAnnouncements(!nbt.contains("Auto") || nbt.getBoolean("Auto"), platforms, rows);
    }

    // ----------------------------------------------------------------- packet

    public void write(PacketByteBuf buf) {
        buf.writeBoolean(autoPlatforms);
        buf.writeVarInt(platforms.size());
        platforms.forEach(buf::writeLong);
        buf.writeVarInt(rows.size());
        for (Row row : rows) {
            buf.writeByte(row.event().ordinal());
            buf.writeVarInt(row.seconds());
            buf.writeVarInt(row.repeats());
            buf.writeVarInt(row.gapSeconds());
            buf.writeBoolean(row.enabled());
            buf.writeString(row.text(), MAX_TEXT);
        }
    }

    /** Reads (and clamps) what {@link #write} wrote; never trusts the counts. */
    public static TrainAnnouncements read(PacketByteBuf buf) {
        boolean auto = buf.readBoolean();
        int platformCount = buf.readVarInt();
        if (platformCount < 0 || platformCount > MAX_PLATFORMS) {
            throw new IllegalArgumentException("Too many platforms: " + platformCount);
        }
        List<Long> platforms = new ArrayList<>(platformCount);
        for (int i = 0; i < platformCount; i++) {
            platforms.add(buf.readLong());
        }
        int rowCount = buf.readVarInt();
        if (rowCount < 0 || rowCount > MAX_ROWS) {
            throw new IllegalArgumentException("Too many rows: " + rowCount);
        }
        List<Row> rows = new ArrayList<>(rowCount);
        for (int i = 0; i < rowCount; i++) {
            rows.add(new Row(Event.byIndex(buf.readByte()), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readBoolean(), buf.readString(MAX_TEXT)));
        }
        return new TrainAnnouncements(auto, platforms, rows);
    }
}
