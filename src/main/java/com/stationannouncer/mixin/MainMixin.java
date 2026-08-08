package com.stationannouncer.mixin;

import com.stationannouncer.mtraddon.dispatch.DispatchWebSetup;
import org.mtr.core.Main;
import org.mtr.core.servlet.Webserver;
import org.mtr.core.simulation.Simulator;
import org.mtr.libraries.it.unimi.dsi.fastutil.objects.ObjectImmutableList;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * <b>What:</b> wraps the single {@code Webserver.start()} call inside
 * {@code org.mtr.core.Main}'s constructor so the dispatch servlets are registered on
 * MTR's own embedded Jetty instance in the last moment before Jetty starts (servlets
 * cannot be added to a started context). Bytecode-verified against MTR
 * FABRIC-4.0.1+1.20.4 with {@code javap -c}: the constructor
 * {@code (Ljava/nio/file/Path;IZZLjava/util/function/Consumer;[Ljava/lang/String;)V}
 * contains <em>exactly one</em> {@code invokevirtual Webserver.start()V} (offset 208),
 * on the {@code port > 0} branch, and {@code putfield simulators} (@85) precedes it — so
 * the shadowed field is already assigned when the handler runs, and with the webserver
 * disabled the handler simply never runs.
 *
 * <p><b>Why a {@code @Redirect} and not an {@code @Inject}:</b> upstream SpongePowered
 * Mixin restricts {@code @Inject} into {@code <init>} to RETURN/TAIL-style injection
 * points; redirects (which rewrite an existing call site) are fully supported anywhere
 * after the super-constructor call, which offset 208 comfortably is. The handler does our
 * setup and then performs the original call itself, so MTR's behaviour is byte-for-byte
 * unchanged apart from the added servlets. If {@link DispatchWebSetup#install} throws it
 * contains the failure internally; {@code start()} is called unconditionally afterwards.
 *
 * <p><b>Why a mixin at all:</b> the sanctioned embedder hook
 * ({@code additionalWebserverSetup}, 5th constructor arg) is owned by MTR itself — the
 * dedicated server passes null and {@code InitClient} installs its own via
 * {@code Init.createWebserverSetup}, which we must not clobber. There is no other
 * extension point before {@code start()}. This also cleanly ignores InitClient's separate
 * client-side resource-pack webserver, which is a bare {@code Webserver} and never
 * constructs a {@code Main}.</p>
 *
 * <p><b>Thread:</b> whatever thread constructs {@code Main} — the server thread, inside
 * MTR's server-started handler. Everything downstream (servlet requests, SSE writes) runs
 * on Jetty workers / the streamer daemon and reads only the volatile registry.</p>
 *
 * <p><b>Toggle:</b> {@code dispatch.enabled} in
 * {@code config/station-announcer-addon.json}; when off {@code install} returns after a
 * single volatile-cached config read and nothing is registered.</p>
 */
@Mixin(value = Main.class, remap = false)
public abstract class MainMixin {
    @Shadow
    @Final
    private ObjectImmutableList<Simulator> simulators;

    @Redirect(
            method = "<init>(Ljava/nio/file/Path;IZZLjava/util/function/Consumer;[Ljava/lang/String;)V",
            at = @At(value = "INVOKE", target = "Lorg/mtr/core/servlet/Webserver;start()V"),
            remap = false
    )
    private void stationAnnouncer$installDispatchServlets(Webserver webserver) {
        DispatchWebSetup.install(webserver, simulators);
        webserver.start();
    }
}
