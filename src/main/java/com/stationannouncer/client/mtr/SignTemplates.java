package com.stationannouncer.client.mtr;

import com.stationannouncer.mtr.sign.SignSpec;
import com.stationannouncer.mtr.sign.SignSpec.Align;
import com.stationannouncer.mtr.sign.SignSpec.Draft;
import com.stationannouncer.mtr.sign.SignSpec.Tile;
import com.stationannouncer.mtr.sign.SignSpec.TileType;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import java.util.ArrayList;
import java.util.List;

/**
 * The standard MTA wordings as one-click starting points. Each fills a face
 * with rows of ordinary tiles, so everything is editable afterwards.
 */
@Environment(EnvType.CLIENT)
final class SignTemplates {
    static final String[] NAMES = {
            "Uptown & Bronx →",
            "← Downtown & Bklyn",
            "Both directions",
            "Exit →",
            "Station plate",
            "Line + destination",
            "Transfer to …",
            "No entry",
            "Platform: name + lines",
            "Elevator →",
            "Stairs / escalator →",
            "No exit (red field)",
            "Late-night service note",
            "Railroad / bus transfer",
            "Entrance: name, lines, hours",
    };

    private SignTemplates() {
    }

    private static Tile auto() {
        return new Tile(TileType.BULLETS, "", "auto", 0, List.of());
    }

    private static Tile symbol(int index) {
        return Tile.arrow(SignSymbols.FIRST + index, "");
    }

    private static Draft.RowDraft row(Align align, Tile... tiles) {
        Draft.RowDraft row = new Draft.RowDraft();
        row.align = align;
        row.tiles.addAll(List.of(tiles));
        return row;
    }

    static List<Draft.RowDraft> rows(int index, boolean tall) {
        List<Draft.RowDraft> rows = new ArrayList<>();
        switch (index) {
            case 0 -> rows.add(row(Align.LEFT, auto(), Tile.text("Uptown & The Bronx"), Tile.arrow(0, "right")));
            case 1 -> rows.add(row(Align.LEFT, Tile.arrow(4, "left"), auto(), Tile.text("Downtown & Brooklyn")));
            case 2 -> {
                rows.add(row(Align.LEFT, auto(), Tile.text("Uptown & The Bronx"), Tile.arrow(0, "right")));
                rows.add(row(Align.LEFT, Tile.arrow(4, "left"), auto(), Tile.text("Downtown & Brooklyn")));
            }
            case 3 -> rows.add(row(Align.LEFT, Tile.of(TileType.EXIT), Tile.arrow(0, "right")));
            case 4 -> rows.add(row(Align.CENTER, Tile.of(TileType.STATION_NAME)));
            case 5 -> rows.add(row(Align.LEFT, Tile.of(TileType.DESTINATION), Tile.arrow(0, "right")));
            case 6 -> rows.add(row(Align.LEFT, Tile.text("Transfer to"), auto(), Tile.arrow(0, "right")));
            case 7 -> rows.add(row(Align.CENTER, Tile.bullets(List.of(RouteBullets.NO_ENTRY)), Tile.text("No entry")));
            case 8 -> {
                rows.add(row(Align.CENTER, Tile.of(TileType.STATION_NAME)));
                rows.add(row(Align.CENTER, auto()));
            }
            case 9 -> rows.add(row(Align.LEFT, symbol(SignSymbols.WHEELCHAIR), symbol(SignSymbols.ELEVATOR),
                    Tile.text("Elevator to street"), Tile.arrow(0, "right")));
            case 10 -> rows.add(row(Align.LEFT, symbol(SignSymbols.STAIRS), symbol(SignSymbols.ESCALATOR),
                    Tile.text("Mezzanine & exit"), Tile.arrow(1, "right")));
            case 11 -> rows.add(row(Align.CENTER, new Tile(TileType.TEXT, "No exit", "red", 0, List.of())));
            case 12 -> rows.add(row(Align.LEFT, auto(),
                    new Tile(TileType.TEXT, "Late nights\nall trains stop here", "yellow", 1, List.of())));
            case 13 -> rows.add(row(Align.LEFT, Tile.text("Transfer"), Tile.of(TileType.RULE),
                    new Tile(TileType.BADGE, "LIRR", "blue", 0, List.of()),
                    new Tile(TileType.BADGE, "M15 SBS", "blue", 0, List.of()),
                    symbol(SignSymbols.BUS), Tile.arrow(0, "right")));
            case 14 -> {
                rows.add(row(Align.LEFT, Tile.of(TileType.STATION_NAME)));
                rows.add(row(Align.LEFT, auto(), Tile.of(TileType.RULE),
                        new Tile(TileType.TEXT, "Open 24 hours\nOMNY & MetroCard", "", 1, List.of())));
            }
            default -> rows.add(row(Align.LEFT, Tile.text("")));
        }
        return rows;
    }
}
