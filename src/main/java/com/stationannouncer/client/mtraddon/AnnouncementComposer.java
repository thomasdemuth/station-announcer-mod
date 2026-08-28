package com.stationannouncer.client.mtraddon;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.mtr.core.data.VehicleExtraData;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders a per-route announcement template into the spoken/chat line for the
 * on-board next-station announcement (the {@code VehicleExtensionMixin} calls
 * this in place of MTR's stock wording).
 *
 * <p><b>Text blocks</b> are plain text. <b>Code blocks</b>:</p>
 * <ul>
 *   <li>{@code {next}} — next station name</li>
 *   <li>{@code {station}} — the station currently being departed</li>
 *   <li>{@code {dest}} — the route's destination</li>
 *   <li>{@code {route}} — the line name (before MTR's {@code ||} separator)</li>
 *   <li>{@code {number}} — the route number</li>
 *   <li>{@code {interchanges}} — connecting routes at the next station, joined
 *       "the A, the B and the C" style; empty when there are none</li>
 * </ul>
 * <p>Conditional sections keep templates clean without a scripting language:</p>
 * <ul>
 *   <li>{@code [interchange]…[/interchange]} — kept only when the next station
 *       has at least one connection</li>
 *   <li>{@code [terminus]…[/terminus]} — kept only when the next station is the
 *       route's destination</li>
 *   <li>{@code [enroute]…[/enroute]} — the opposite of {@code [terminus]}</li>
 * </ul>
 * <p>All names are the first language of MTR's {@code "Eng|Other"} strings. The
 * result goes through MTR's own {@code IDrawing.narrateOrAnnounce}, so the
 * player's TTS / chat announcement options keep working unchanged.</p>
 */
@Environment(EnvType.CLIENT)
public final class AnnouncementComposer {
    private AnnouncementComposer() {
    }

    /** Renders {@code template} against the vehicle's current leg. */
    public static String render(String template, VehicleExtraData extra) {
        List<String> interchanges = new ArrayList<>();
        extra.iterateInterchanges((stationName, colors) ->
                colors.forEach((color, routeNames) -> routeNames.forEach(name -> {
                    String cleaned = firstLang(name).trim();
                    if (!cleaned.isEmpty() && !interchanges.contains(cleaned)) {
                        interchanges.add(cleaned);
                    }
                })));
        String next = firstLang(extra.getNextStationName());
        String destination = firstLang(extra.getThisRouteDestination());
        boolean terminus = !next.isEmpty() && next.equalsIgnoreCase(destination);
        return renderWithValues(template, next, firstLang(extra.getThisStationName()), destination,
                firstLang(extra.getThisRouteName().split("\\|\\|")[0]), extra.getThisRouteNumber(),
                interchanges, terminus);
    }

    /** The substitution core, shared with the template editor's live preview. */
    public static String renderWithValues(String template, String next, String station, String destination,
                                          String route, String number, List<String> interchanges, boolean terminus) {
        String text = template;
        text = conditional(text, "interchange", !interchanges.isEmpty());
        text = conditional(text, "terminus", terminus);
        text = conditional(text, "enroute", !terminus);
        text = text.replace("{next}", next)
                .replace("{station}", station)
                .replace("{dest}", destination)
                .replace("{route}", route)
                .replace("{number}", number)
                .replace("{interchanges}", joinNames(interchanges));
        return text.replaceAll("\\s+", " ").trim();
    }

    /** Speaks/prints one rendered announcement through MTR's own pipeline. */
    public static void announce(String rendered) {
        if (rendered.isEmpty()) {
            return;
        }
        ObjectArrayList<org.mtr.mapping.holder.MutableText> chat = new ObjectArrayList<>();
        chat.add(org.mtr.mapping.mapper.TextHelper.literal(rendered));
        org.mtr.mod.client.IDrawing.narrateOrAnnounce(rendered, chat);
    }

    /** Keeps or strips a {@code [tag]…[/tag]} section; unmatched tags are left alone. */
    private static String conditional(String text, String tag, boolean keep) {
        String open = "[" + tag + "]";
        String close = "[/" + tag + "]";
        while (true) {
            int start = text.indexOf(open);
            if (start < 0) {
                return text;
            }
            int end = text.indexOf(close, start);
            if (end < 0) {
                return text;
            }
            String inner = keep ? text.substring(start + open.length(), end) : "";
            text = text.substring(0, start) + inner + text.substring(end + close.length());
        }
    }

    /** "A", "A and B", "A, B and C" — natural for speech. */
    private static String joinNames(List<String> names) {
        if (names.isEmpty()) {
            return "";
        }
        if (names.size() == 1) {
            return names.get(0);
        }
        return String.join(", ", names.subList(0, names.size() - 1)) + " and " + names.get(names.size() - 1);
    }

    private static String firstLang(String raw) {
        return raw == null ? "" : raw.split("\\|")[0];
    }
}
