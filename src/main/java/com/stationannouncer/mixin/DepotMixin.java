package com.stationannouncer.mixin;

import com.stationannouncer.mtraddon.PlatformGroupEngine;
import com.stationannouncer.mtraddon.disruption.StopOverlayEngine;
import org.mtr.core.data.Data;
import org.mtr.core.data.Depot;
import org.mtr.core.data.TransportMode;
import org.mtr.core.generated.data.DepotSchema;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <b>What:</b> two non-cancelling hooks into {@code org.mtr.core.data.Depot} for
 * Feature 5 (dynamic platform selection), both delegating to
 * {@link PlatformGroupEngine}:
 * <ul>
 *   <li>{@code generateMainRoute(Depot$OnGenerationComplete)V} HEAD (private,
 *       javap/bytecode-verified against MTR FABRIC-4.0.1+1.20.4) — the single
 *       place the depot's {@code SidingPathFinder} chain is built from the
 *       private {@code platformsInRoute} list, and the only moment "which
 *       platform does this stop use" is read for path generation. The engine
 *       rotates each configured group and swaps the list entries BEFORE the
 *       chain-building loop runs; {@code Depot.tick}'s completion callback
 *       ({@code siding.generateRoute(platformsInRoute.get(0)..., size, ...)})
 *       reads the same swapped list, so first/last platforms stay consistent.
 *       The user's route definition ({@code Route.getRoutePlatforms()}) is
 *       never modified.</li>
 *   <li>{@code writeRouteCache(Long2ObjectOpenHashMap)V} TAIL (public,
 *       javap-verified) — {@code Data.sync()} rebuilds {@code platformsInRoute}
 *       from the routes' original platforms on every sync; the engine re-applies
 *       the last generation's choices (no rotation advance) so
 *       {@code getVehiclePlatformRouteInfo} keeps matching the baked path.
 *       Runs on client Data too — the engine bails unless
 *       {@code data instanceof Simulator}.</li>
 * </ul>
 *
 * <p><b>Why a mixin:</b> both members are private/have no events, and the swap
 * must land between the route-cache build and the finder-chain build — there is
 * no API moment in between. The handlers omit the target methods' arguments
 * (Mixin allows a bare {@code CallbackInfo} signature), which also avoids
 * naming the private {@code Depot$OnGenerationComplete} type.</p>
 *
 * <p><b>Thread:</b> the per-dimension SIMULATOR thread (or the server thread
 * when {@code useThreadedSimulation} is off) — depot generation and data syncs
 * run there. The engine reads only volatile immutable snapshots, the
 * simulator's own data and its concurrent runtime maps.</p>
 *
 * <p><b>Toggle:</b> {@code dynamicPlatforms.enabled} in
 * {@code config/station-announcer-addon.json}; when off (or when no groups
 * exist) both hooks bail out in O(1) with zero allocation. Both are cold paths
 * (depot regeneration / data sync, never per tick).</p>
 *
 * <p>The mixin extends {@code DepotSchema} (like {@link VehicleMixin} extends
 * {@code VehicleSchema}) to reach the protected {@code data} field; the shadowed
 * {@code platformsInRoute} uses a wildcard element type because its real element
 * type ({@code Depot$PlatformRouteDetails}) is package-private — field
 * descriptors are erased, so the shadow still binds.</p>
 */
@Mixin(value = Depot.class, remap = false)
public abstract class DepotMixin extends DepotSchema {
    /** javap-verified: {@code private final ObjectArrayList<Depot$PlatformRouteDetails> platformsInRoute} on 4.0.1 Depot. */
    @Shadow
    @Final
    private ObjectArrayList<?> platformsInRoute;

    protected DepotMixin(TransportMode transportMode, Data data) {
        super(transportMode, data);
    }

    /**
     * Feature 5 + Feature 6a share this hook, and the ORDER matters, which is why
     * both engines are called from one handler instead of two injectors (injector
     * ordering between mixins is not part of the contract):
     * <ol>
     *   <li>{@link StopOverlayEngine#restorePristine} undoes the temporary stop
     *       overlay we applied last time, so the list is exactly as MTR built it
     *       and {@code PlatformGroupEngine}'s walk-size sanity check still
     *       matches;</li>
     *   <li>{@code PlatformGroupEngine} rotates its platform groups (in-place
     *       field swaps — the list's size never changes);</li>
     *   <li>{@link StopOverlayEngine#onGenerateMainRoute} re-applies the temporary
     *       overlay (which DOES change the size) on top of those choices.</li>
     * </ol>
     */
    @Inject(method = "generateMainRoute(Lorg/mtr/core/data/Depot$OnGenerationComplete;)V", at = @At("HEAD"))
    private void stationAnnouncer$chooseGroupPlatforms(CallbackInfo ci) {
        StopOverlayEngine.restorePristine((Depot) (Object) this, data, platformsInRoute);
        PlatformGroupEngine.onGenerateMainRoute((Depot) (Object) this, data, platformsInRoute);
        StopOverlayEngine.onGenerateMainRoute((Depot) (Object) this, data, platformsInRoute);
    }

    /**
     * Same ordering rule on the rebuild path: MTR has just refilled
     * {@code platformsInRoute} from the routes' original platforms, Feature 5
     * re-applies its group choices, and Feature 6a then re-applies the temporary
     * stop overlay so {@code getVehiclePlatformRouteInfo} keeps matching the baked
     * path between generations.
     */
    @Inject(method = "writeRouteCache(Lorg/mtr/libraries/it/unimi/dsi/fastutil/longs/Long2ObjectOpenHashMap;)V", at = @At("TAIL"))
    private void stationAnnouncer$reapplyGroupPlatforms(CallbackInfo ci) {
        PlatformGroupEngine.onWriteRouteCache((Depot) (Object) this, data, platformsInRoute);
        StopOverlayEngine.onWriteRouteCache((Depot) (Object) this, data, platformsInRoute);
    }
}
