package com.stationannouncer.mtraddon.crew;

import net.minecraft.network.PacketByteBuf;
import java.util.ArrayList;
import java.util.List;

/**
 * What the server tells a crew about the line ahead and their timetable slot
 * ({@link CrewProtocol#FEED_S2C}, twice a second while crewing).
 *
 * <p><b>Why it exists:</b> MTR syncs a client only the path from its last update up to
 * the train's stopping point ({@code VehicleExtraData.copy(pathUpdateIndex)}); for a
 * manual train that is wherever it stands, so the client cannot see the next stop,
 * signal or limit. The server walks the full path instead ({@link FeedBuilder}).</p>
 *
 * @param stops       upcoming platform stops (the one the train stands at first, if any — its
 *                    distance is then the offset from the stopping mark: + short, − overrun)
 * @param atPlatform  the train stands at {@code stops[0]}
 * @param limitKph    the limit where the train is now (0 = unknown)
 * @param nextLimitKph next different limit ahead, 0 = none in range
 * @param aspect      first signal ahead: -1 none, 0 clear, 1 caution, 2 danger
 * @param slot        the claimed timetable slot, or null
 */
public record CabFeed(long vehicleId, List<Stop> stops, boolean atPlatform, int limitKph, int nextLimitKph,
                      float nextLimitDistance, byte aspect, float signalDistance, Slot slot) {

    public static final int MAX_STOPS = 12;
    public static final int MAX_NAME = 64;

    public record Stop(long platformId, String name, float distance) {
    }

    /**
     * A claimed slot. Times are epoch millis on the server clock; 0 = unknown.
     * {@code deviation} is the live lateness (positive = late), unwrapped. The route fields are the
     * slot's service (MTR calls the train "not in service" until its first stop, so the client
     * cannot read them off the vehicle before then): name and number first-language only, colour RGB.
     */
    public record Slot(String label, String routeName, String routeNumber, int routeColor, String destination,
                       byte state, long yardTime, long deviation, List<Row> rows, int currentRow,
                       long departAt, long dwellMillis) {
    }

    /** One stop of the slot: scheduled arrival/departure and the actual or expected time. */
    public record Row(String name, long scheduledArrival, long scheduledDeparture, long actualArrival,
                      long actualDeparture) {
    }

    public static final byte SLOT_PENDING = 0;
    public static final byte SLOT_RUNNING = 1;
    public static final byte SLOT_ENDED = 2;

    public void write(PacketByteBuf buf) {
        buf.writeLong(vehicleId);
        buf.writeVarInt(stops.size());
        for (Stop stop : stops) {
            buf.writeLong(stop.platformId());
            buf.writeString(cap(stop.name()), MAX_NAME);
            buf.writeFloat(stop.distance());
        }
        buf.writeBoolean(atPlatform);
        buf.writeVarInt(limitKph);
        buf.writeVarInt(nextLimitKph);
        buf.writeFloat(nextLimitDistance);
        buf.writeByte(aspect);
        buf.writeFloat(signalDistance);
        buf.writeBoolean(slot != null);
        if (slot != null) {
            buf.writeString(cap(slot.label()), MAX_NAME);
            buf.writeString(cap(slot.routeName()), MAX_NAME);
            buf.writeString(cap(slot.routeNumber()), MAX_NAME);
            buf.writeInt(slot.routeColor());
            buf.writeString(cap(slot.destination()), MAX_NAME);
            buf.writeByte(slot.state());
            buf.writeLong(slot.yardTime());
            buf.writeLong(slot.deviation());
            buf.writeVarInt(slot.rows().size());
            for (Row row : slot.rows()) {
                buf.writeString(cap(row.name()), MAX_NAME);
                buf.writeLong(row.scheduledArrival());
                buf.writeLong(row.scheduledDeparture());
                buf.writeLong(row.actualArrival());
                buf.writeLong(row.actualDeparture());
            }
            buf.writeVarInt(slot.currentRow());
            buf.writeLong(slot.departAt());
            buf.writeLong(slot.dwellMillis());
        }
    }

    public static CabFeed read(PacketByteBuf buf) {
        long vehicleId = buf.readLong();
        int count = Math.min(buf.readVarInt(), MAX_STOPS);
        List<Stop> stops = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            stops.add(new Stop(buf.readLong(), buf.readString(MAX_NAME), buf.readFloat()));
        }
        boolean atPlatform = buf.readBoolean();
        int limit = buf.readVarInt();
        int nextLimit = buf.readVarInt();
        float nextLimitDistance = buf.readFloat();
        byte aspect = buf.readByte();
        float signalDistance = buf.readFloat();
        Slot slot = null;
        if (buf.readBoolean()) {
            String label = buf.readString(MAX_NAME);
            String routeName = buf.readString(MAX_NAME);
            String routeNumber = buf.readString(MAX_NAME);
            int routeColor = buf.readInt();
            String destination = buf.readString(MAX_NAME);
            byte state = buf.readByte();
            long yard = buf.readLong();
            long deviation = buf.readLong();
            int rowCount = Math.min(buf.readVarInt(), 64);
            List<Row> rows = new ArrayList<>(rowCount);
            for (int i = 0; i < rowCount; i++) {
                rows.add(new Row(buf.readString(MAX_NAME), buf.readLong(), buf.readLong(), buf.readLong(), buf.readLong()));
            }
            slot = new Slot(label, routeName, routeNumber, routeColor, destination, state, yard, deviation, rows,
                    buf.readVarInt(), buf.readLong(), buf.readLong());
        }
        return new CabFeed(vehicleId, stops, atPlatform, limit, nextLimit, nextLimitDistance, aspect, signalDistance, slot);
    }

    private static String cap(String s) {
        return s == null ? "" : s.length() > MAX_NAME ? s.substring(0, MAX_NAME) : s;
    }
}
