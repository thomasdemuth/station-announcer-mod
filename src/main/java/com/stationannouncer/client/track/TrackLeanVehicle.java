package com.stationannouncer.client.track;

/**
 * Duck interface the {@code VehicleExtensionLeanMixin} adds to MTR's client vehicle
 * ({@code org.mtr.mod.data.VehicleExtension}): the vehicle's cached
 * {@link TrackVehicleLeanMath.PathTilts}. MTR builds a NEW VehicleExtension for every vehicle
 * update, so the cache lives exactly as long as the path it describes.
 */
public interface TrackLeanVehicle {
    TrackVehicleLeanMath.PathTilts stationannouncer$leanTilts();

    void stationannouncer$setLeanTilts(TrackVehicleLeanMath.PathTilts tilts);
}
