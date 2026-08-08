package com.stationannouncer.mixin;

import org.mtr.core.data.Siding;
import org.mtr.core.data.Vehicle;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArraySet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * <b>What:</b> read access to {@code Siding.vehicles}
 * ({@code private final ObjectArraySet<Vehicle>}, javap-verified against MTR
 * FABRIC-4.0.1+1.20.4 — note master drifted to a {@code Long2ObjectOpenHashMap
 * vehicleIdMap}; the 4.0.1 jar is the contract) so the dispatch sampler can iterate a
 * siding's active vehicles.
 *
 * <p><b>Why a mixin:</b> 4.0.1 exposes no vehicle iteration API on Siding
 * ({@code getVehicleById} etc. are master-only); the only public traversal,
 * {@code iterateVehiclesAndRidingEntities}, visits riding entities, not vehicles.</p>
 *
 * <p><b>Thread:</b> called exclusively on the owning dimension's SIMULATOR thread from
 * {@link com.stationannouncer.mtraddon.dispatch.DispatchSampler} (enqueued via
 * {@code simulator.run}), the same thread that mutates the set — no concurrent access.</p>
 *
 * <p><b>Toggle:</b> {@code dispatch.enabled} — the sampler only runs while the dispatch
 * streamer has connected clients, and the streamer is unreachable when the feature is
 * off (servlets never registered).</p>
 */
@Mixin(value = Siding.class, remap = false)
public interface SidingVehiclesAccessor {
    @Accessor(value = "vehicles", remap = false)
    ObjectArraySet<Vehicle> stationAnnouncer$getVehicles();
}
