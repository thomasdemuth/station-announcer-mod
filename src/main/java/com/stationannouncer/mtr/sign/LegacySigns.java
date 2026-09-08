package com.stationannouncer.mtr.sign;

import com.stationannouncer.mtr.StationDecorBlockEntity;
import net.minecraft.block.Block;
import java.util.List;

/**
 * The text signs that existed before the sign system — entrance railing sign,
 * el entrance sign, el name boards, el windscreen / railing sign courses —
 * now draw through the same layout. A block that was never edited in the new
 * editor has no {@code Sign} JSON; this derives one from its legacy fields
 * (custom name, per-face on/off, per-face route bullets) so every old
 * placement keeps its content. Saving in the editor writes the JSON, after
 * which the legacy fields are ignored for that block.
 */
public final class LegacySigns {
    /** How a legacy block turned its fields into a sign. */
    public enum Kind {
        /** Entrance railing sign: name over a row of small bullets, both faces independent. */
        RAILING,
        /** El entrance sign: bullets beside the name, both faces independent. */
        ENTRANCE,
        /** El name boards / sign courses: one centred upper-case station name, plain black. */
        BOARD,
        /** Not a legacy sign block. */
        NONE
    }

    private LegacySigns() {
    }

    public static Kind kindOf(Block block) {
        if (block instanceof com.stationannouncer.mtr.RailingSignBlock) {
            return Kind.RAILING;
        }
        if (block instanceof com.stationannouncer.mtr.ElEntranceSignBlock) {
            return Kind.ENTRANCE;
        }
        if (block instanceof com.stationannouncer.mtr.ElNameBoardBlock
                || block instanceof com.stationannouncer.mtr.ElWallSignBlock
                || block instanceof com.stationannouncer.mtr.ElRailingSignBlock) {
            return Kind.BOARD;
        }
        return Kind.NONE;
    }

    /**
     * The sign a legacy block draws: its stored JSON when it has one, else one
     * derived from the merged legacy fields ({@code customName} and the
     * per-face route lists are the run's "first segment with a value" results;
     * {@code front}/{@code back} are the run's AND).
     */
    public static SignFaces derive(Kind kind, String customName, boolean front, boolean back,
                                  List<String> frontRoutes, List<String> backRoutes) {
        return switch (kind) {
            case RAILING -> new SignFaces(front, railing(customName, frontRoutes),
                    back ? SignFaces.BackMode.OWN : SignFaces.BackMode.OFF, railing(customName, backRoutes));
            case ENTRANCE -> new SignFaces(front, entrance(customName, frontRoutes),
                    back ? SignFaces.BackMode.OWN : SignFaces.BackMode.OFF, entrance(customName, backRoutes));
            default -> SignFaces.of(board(customName));
        };
    }

    /** Convenience for a lone block entity (no run merging). */
    public static SignFaces of(StationDecorBlockEntity entity) {
        if (entity.getSign() != null) {
            return entity.getSign();
        }
        return derive(kindOf(entity.getCachedState().getBlock()), entity.getCustomName(), entity.isSignFront(),
                entity.isSignBack(), entity.getFrontRoutes(), entity.getBackRoutes());
    }

    private static SignSpec.Tile name(String customName, boolean upper) {
        return new SignSpec.Tile(SignSpec.TileType.STATION_NAME, customName, "", upper ? 1 : 0, List.of());
    }

    private static SignSpec railing(String customName, List<String> routes) {
        if (routes.isEmpty()) {
            return new SignSpec(SignSpec.Style.PLAIN, List.of(SignSpec.Row.of(name(customName, false))));
        }
        return new SignSpec(SignSpec.Style.PLAIN, List.of(
                SignSpec.Row.of(name(customName, false)),
                SignSpec.Row.of(SignSpec.Tile.bullets(routes))));
    }

    private static SignSpec entrance(String customName, List<String> routes) {
        if (routes.isEmpty()) {
            return new SignSpec(SignSpec.Style.PLAIN, List.of(SignSpec.Row.centered(name(customName, false))));
        }
        return new SignSpec(SignSpec.Style.PLAIN, List.of(
                SignSpec.Row.of(SignSpec.Tile.bullets(routes), name(customName, false))));
    }

    private static SignSpec board(String customName) {
        return new SignSpec(SignSpec.Style.PLAIN, List.of(SignSpec.Row.centered(name(customName, true))));
    }
}
