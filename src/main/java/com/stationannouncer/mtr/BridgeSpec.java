package com.stationannouncer.mtr;

import net.minecraft.block.BlockState;
import net.minecraft.command.argument.BlockArgumentParser;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;

import java.util.Locale;

/**
 * Everything the Bridge Creator builds, as one plain settings object: which
 * tracks it covers, the deck, the girders under it, the railing on top, the
 * piers and their spacing, and the arches between piers. Every part has its
 * own material (a block-state string such as {@code minecraft:stone_bricks}
 * or {@code minecraft:oak_slab[type=top]}). The same object drives the
 * server build ({@link BridgeBuilder}) and the client's live preview, and
 * presets are just saved copies of it.
 *
 * <p>Persisted in the item's NBT under {@link #TAG} and, for user presets,
 * as JSON (Gson handles the public fields directly). Missing keys fall back
 * to the field defaults, so old items and old presets keep working.
 */
public final class BridgeSpec {
    public static final String TAG = "Bridge";

    public enum TrackMode { AUTO, SINGLE, MANUAL }

    public enum GirderStyle { NONE, EDGES, TRACKS, BOTH, FULL }

    public enum PierStyle { NONE, EDGES, CENTRE, TRACKS, TWIN, WALL }

    public enum ArchStyle { NONE, FILLED, OPEN }

    /** The material slots a sneak-click in the world (or the picker) can fill. */
    public enum Slot { DECK, EDGE, GIRDER, RAILING, PIER, CAP, FOOTING, ARCH }

    public static final int MIN_REACH = 2, MAX_REACH = 16;
    public static final int MAX_THICKNESS = 4;
    public static final int MAX_OVERHANG = 6;
    public static final int MAX_EDGE_WIDTH = 3;
    public static final int MAX_GIRDER_DEPTH = 6;
    public static final int MAX_RAILING_HEIGHT = 3;
    public static final int MAX_RAILING_INSET = 3;
    public static final int MIN_SPACING = 2, MAX_SPACING = 32;
    public static final int MAX_PIER_THICKNESS = 3;
    public static final int MAX_PIER_INSET = 4;
    public static final int MAX_RISE = 12;
    public static final int MAX_PREVIEW_TRACKS = 4;

    // tracks
    public TrackMode trackMode = TrackMode.AUTO;
    /** How far sideways (blocks) auto mode looks for parallel tracks. */
    public int trackReach = 8;
    /** Tracks shown in the settings preview (the real build uses the tracks it finds). */
    public int previewTracks = 2;

    // deck
    public boolean deck = true;
    public String deckMaterial = "minecraft:stone_bricks";
    public int deckThickness = 1;
    /** Deck cells beyond the outermost tracks on each side. */
    public int overhang = 2;
    /** Material of the outer {@link #edgeWidth} deck cells; empty = same as the deck. */
    public String edgeMaterial = "";
    public int edgeWidth = 1;

    // girders (hang under the deck)
    public GirderStyle girderStyle = GirderStyle.EDGES;
    public String girderMaterial = "minecraft:stone_bricks";
    public int girderDepth = 1;

    // railing (on top of the deck edges)
    public boolean railing = true;
    public String railingMaterial = "minecraft:stone_brick_wall";
    public int railingHeight = 1;
    /** Cells in from the deck edge. */
    public int railingInset = 0;

    // piers
    public PierStyle pierStyle = PierStyle.EDGES;
    public String pierMaterial = "minecraft:stone_bricks";
    /** Blocks of rail between pier sets. */
    public int pierSpacing = 8;
    /** Blocks along the rail each pier occupies. */
    public int pierThickness = 1;
    /** Edge-style legs sit this many cells in from the deck edge. */
    public int pierInset = 1;
    public boolean pierCap = false;
    public String capMaterial = "minecraft:stone_bricks";
    public boolean footing = false;
    public String footingMaterial = "minecraft:stone_bricks";

    // arches between piers
    public ArchStyle archStyle = ArchStyle.NONE;
    public String archMaterial = "minecraft:stone_bricks";
    /** Depth of the arch below the deck at the piers, in blocks. */
    public int archRise = 4;

    /** Which slot the next sneak-click on a world block fills. */
    public Slot pickTarget = Slot.DECK;

    public BridgeSpec copy() {
        return fromNbt(toNbt());
    }

    public String material(Slot slot) {
        return switch (slot) {
            case DECK -> deckMaterial;
            case EDGE -> edgeMaterial;
            case GIRDER -> girderMaterial;
            case RAILING -> railingMaterial;
            case PIER -> pierMaterial;
            case CAP -> capMaterial;
            case FOOTING -> footingMaterial;
            case ARCH -> archMaterial;
        };
    }

    public void setMaterial(Slot slot, String value) {
        String v = value == null ? "" : value.trim();
        switch (slot) {
            case DECK -> deckMaterial = v;
            case EDGE -> edgeMaterial = v;
            case GIRDER -> girderMaterial = v;
            case RAILING -> railingMaterial = v;
            case PIER -> pierMaterial = v;
            case CAP -> capMaterial = v;
            case FOOTING -> footingMaterial = v;
            case ARCH -> archMaterial = v;
        }
    }

    /** Clamps every numeric field into its allowed range (called after every read). */
    public BridgeSpec clamp() {
        trackReach = clamp(trackReach, MIN_REACH, MAX_REACH);
        previewTracks = clamp(previewTracks, 1, MAX_PREVIEW_TRACKS);
        deckThickness = clamp(deckThickness, 1, MAX_THICKNESS);
        overhang = clamp(overhang, 0, MAX_OVERHANG);
        edgeWidth = clamp(edgeWidth, 1, MAX_EDGE_WIDTH);
        girderDepth = clamp(girderDepth, 1, MAX_GIRDER_DEPTH);
        railingHeight = clamp(railingHeight, 1, MAX_RAILING_HEIGHT);
        railingInset = clamp(railingInset, 0, MAX_RAILING_INSET);
        pierSpacing = clamp(pierSpacing, MIN_SPACING, MAX_SPACING);
        pierThickness = clamp(pierThickness, 1, MAX_PIER_THICKNESS);
        pierInset = clamp(pierInset, 0, MAX_PIER_INSET);
        archRise = clamp(archRise, 1, MAX_RISE);
        if (trackMode == null) trackMode = TrackMode.AUTO;
        if (girderStyle == null) girderStyle = GirderStyle.NONE;
        if (pierStyle == null) pierStyle = PierStyle.NONE;
        if (archStyle == null) archStyle = ArchStyle.NONE;
        if (pickTarget == null) pickTarget = Slot.DECK;
        for (Slot slot : Slot.values()) {
            String m = material(slot);
            if (m == null) {
                setMaterial(slot, "");
            } else if (m.length() > 200) {
                setMaterial(slot, m.substring(0, 200));
            }
        }
        return this;
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    // ------------------------------------------------------------------ NBT

    public NbtCompound toNbt() {
        NbtCompound n = new NbtCompound();
        n.putString("TrackMode", trackMode.name());
        n.putInt("TrackReach", trackReach);
        n.putInt("PreviewTracks", previewTracks);
        n.putBoolean("Deck", deck);
        n.putString("DeckMaterial", deckMaterial);
        n.putInt("DeckThickness", deckThickness);
        n.putInt("Overhang", overhang);
        n.putString("EdgeMaterial", edgeMaterial);
        n.putInt("EdgeWidth", edgeWidth);
        n.putString("GirderStyle", girderStyle.name());
        n.putString("GirderMaterial", girderMaterial);
        n.putInt("GirderDepth", girderDepth);
        n.putBoolean("Railing", railing);
        n.putString("RailingMaterial", railingMaterial);
        n.putInt("RailingHeight", railingHeight);
        n.putInt("RailingInset", railingInset);
        n.putString("PierStyle", pierStyle.name());
        n.putString("PierMaterial", pierMaterial);
        n.putInt("PierSpacing", pierSpacing);
        n.putInt("PierThickness", pierThickness);
        n.putInt("PierInset", pierInset);
        n.putBoolean("PierCap", pierCap);
        n.putString("CapMaterial", capMaterial);
        n.putBoolean("Footing", footing);
        n.putString("FootingMaterial", footingMaterial);
        n.putString("ArchStyle", archStyle.name());
        n.putString("ArchMaterial", archMaterial);
        n.putInt("ArchRise", archRise);
        n.putString("PickTarget", pickTarget.name());
        return n;
    }

    public static BridgeSpec fromNbt(NbtCompound n) {
        BridgeSpec s = new BridgeSpec();
        if (n == null) {
            return s;
        }
        s.trackMode = enumOr(TrackMode.class, n, "TrackMode", s.trackMode);
        s.trackReach = intOr(n, "TrackReach", s.trackReach);
        s.previewTracks = intOr(n, "PreviewTracks", s.previewTracks);
        s.deck = boolOr(n, "Deck", s.deck);
        s.deckMaterial = strOr(n, "DeckMaterial", s.deckMaterial);
        s.deckThickness = intOr(n, "DeckThickness", s.deckThickness);
        s.overhang = intOr(n, "Overhang", s.overhang);
        s.edgeMaterial = strOr(n, "EdgeMaterial", s.edgeMaterial);
        s.edgeWidth = intOr(n, "EdgeWidth", s.edgeWidth);
        s.girderStyle = enumOr(GirderStyle.class, n, "GirderStyle", s.girderStyle);
        s.girderMaterial = strOr(n, "GirderMaterial", s.girderMaterial);
        s.girderDepth = intOr(n, "GirderDepth", s.girderDepth);
        s.railing = boolOr(n, "Railing", s.railing);
        s.railingMaterial = strOr(n, "RailingMaterial", s.railingMaterial);
        s.railingHeight = intOr(n, "RailingHeight", s.railingHeight);
        s.railingInset = intOr(n, "RailingInset", s.railingInset);
        s.pierStyle = enumOr(PierStyle.class, n, "PierStyle", s.pierStyle);
        s.pierMaterial = strOr(n, "PierMaterial", s.pierMaterial);
        s.pierSpacing = intOr(n, "PierSpacing", s.pierSpacing);
        s.pierThickness = intOr(n, "PierThickness", s.pierThickness);
        s.pierInset = intOr(n, "PierInset", s.pierInset);
        s.pierCap = boolOr(n, "PierCap", s.pierCap);
        s.capMaterial = strOr(n, "CapMaterial", s.capMaterial);
        s.footing = boolOr(n, "Footing", s.footing);
        s.footingMaterial = strOr(n, "FootingMaterial", s.footingMaterial);
        s.archStyle = enumOr(ArchStyle.class, n, "ArchStyle", s.archStyle);
        s.archMaterial = strOr(n, "ArchMaterial", s.archMaterial);
        s.archRise = intOr(n, "ArchRise", s.archRise);
        s.pickTarget = enumOr(Slot.class, n, "PickTarget", s.pickTarget);
        return s.clamp();
    }

    /** The spec stored on a creator item (defaults when it was never configured). */
    public static BridgeSpec read(ItemStack stack) {
        NbtCompound nbt = stack.getNbt();
        return fromNbt(nbt != null && nbt.contains(TAG) ? nbt.getCompound(TAG) : null);
    }

    public void write(ItemStack stack) {
        stack.getOrCreateNbt().put(TAG, clamp().toNbt());
    }

    private static int intOr(NbtCompound n, String key, int fallback) {
        return n.contains(key) ? n.getInt(key) : fallback;
    }

    private static boolean boolOr(NbtCompound n, String key, boolean fallback) {
        return n.contains(key) ? n.getBoolean(key) : fallback;
    }

    private static String strOr(NbtCompound n, String key, String fallback) {
        return n.contains(key) ? n.getString(key) : fallback;
    }

    private static <E extends Enum<E>> E enumOr(Class<E> type, NbtCompound n, String key, E fallback) {
        if (!n.contains(key)) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, n.getString(key).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    // ------------------------------------------------------------ materials

    /**
     * Parses a material string into a block state ({@code minecraft:oak_slab[type=top]}
     * works, properties optional). Null for blank or unparseable input, so the
     * caller can skip that part and tell the player.
     */
    public static BlockState parseMaterial(String material) {
        if (material == null || material.isBlank()) {
            return null;
        }
        try {
            BlockState state = BlockArgumentParser.block(Registries.BLOCK.getReadOnlyWrapper(), material.trim(), false).blockState();
            return state.isAir() ? null : state;
        } catch (Exception e) {
            return null;
        }
    }

    /** The inverse of {@link #parseMaterial}: a string that parses back to exactly this state. */
    public static String stringify(BlockState state) {
        return BlockArgumentParser.stringifyBlockState(state);
    }
}
