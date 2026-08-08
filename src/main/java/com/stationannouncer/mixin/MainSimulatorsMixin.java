package com.stationannouncer.mixin;

import com.stationannouncer.mtraddon.disruption.MtrSimulators;
import org.mtr.core.Main;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * <b>What:</b> captures MTR's per-dimension {@code Simulator} list into
 * {@link MtrSimulators} at the RETURN of the {@code org.mtr.core.Main} constructor
 * {@code (Ljava/nio/file/Path;IZZLjava/util/function/Consumer;[Ljava/lang/String;)V}
 * (signature javap-verified against MTR FABRIC-4.0.1+1.20.4, where
 * {@code private final ObjectImmutableList<Simulator> simulators} is assigned early
 * in the constructor and never reassigned).
 *
 * <p><b>Why:</b> the disruption broadcaster has to ask the railway which stations
 * a line calls at, and MTR exposes that only through a {@code Simulator}. There is
 * no public accessor — {@code Main.simulators} is private and {@code Init.main} is
 * a private static — so a capture at construction is the cleanest hook.
 * {@code MainMixin} (the dispatch web layer) already holds the list, but it is
 * populated from a {@code Webserver.start()} redirect that only runs when MTR's
 * webserver is enabled, which disruptions must not depend on. This mixin makes no
 * behavioural change whatsoever: it reads one field and stores the reference.</p>
 *
 * <p><b>Why RETURN:</b> upstream Mixin only allows {@code @Inject} into
 * {@code <init>} at RETURN/TAIL-style points; the field is assigned long before
 * then, so the value is complete.</p>
 *
 * <p><b>Thread:</b> whatever thread constructs {@code Main} — the server thread,
 * inside MTR's server-started handler. The registry is a single volatile
 * reference; it is cleared on SERVER_STOPPING.</p>
 *
 * <p><b>Toggle:</b> none — the capture itself is one field store, and every
 * consumer is gated by {@code disruptions.enabled}. Leaving it unconditional keeps
 * the registry correct if the config is reloaded at runtime.</p>
 */
@Mixin(value = Main.class, remap = false)
public abstract class MainSimulatorsMixin {
    @Shadow
    @Final
    private ObjectImmutableList<Simulator> simulators;

    @Inject(
            method = "<init>(Ljava/nio/file/Path;IZZLjava/util/function/Consumer;[Ljava/lang/String;)V",
            at = @At("RETURN")
    )
    private void stationAnnouncer$captureSimulators(CallbackInfo ci) {
        MtrSimulators.set(simulators);
    }
}
