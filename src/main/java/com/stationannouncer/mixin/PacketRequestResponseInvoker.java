package com.stationannouncer.mixin;

import org.mtr.mapping.holder.ServerPlayerEntity;
import org.mtr.mapping.holder.ServerWorld;
import org.mtr.mod.packet.PacketRequestResponseBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Lets server code push an MTR data update exactly the way MTR's own server-side helpers
 * do ({@code PacketUpdateData.sendDirectlyToServerRail} builds a packet and calls this
 * protected method with the target world): the request goes to MTR's simulator, and the
 * response is broadcast to every player in that world so dashboards stay in step. The
 * player argument may be null for {@code ResponseType.ALL} packets such as
 * {@code PacketUpdateData} (bytecode-verified, 4.0.1).
 */
@Mixin(value = PacketRequestResponseBase.class, remap = false)
public interface PacketRequestResponseInvoker {
    @Invoker("runServerOutbound")
    void stationAnnouncer$runServerOutbound(ServerWorld world, ServerPlayerEntity player);
}
