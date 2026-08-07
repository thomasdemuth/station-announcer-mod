package com.stationannouncer.entity;

import com.stationannouncer.block.BenchBlock;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.world.World;

/**
 * Invisible rideable marker used for sitting on benches. Lives exactly as
 * long as someone is sitting: discards itself when the passenger leaves or
 * the bench below is gone.
 */
public class SeatEntity extends Entity {
    public SeatEntity(EntityType<?> type, World world) {
        super(type, world);
        this.noClip = true;
        this.setInvulnerable(true);
    }

    @Override
    public void tick() {
        super.tick();
        if (!getWorld().isClient
                && (getPassengerList().isEmpty()
                || !(getWorld().getBlockState(getBlockPos()).getBlock() instanceof BenchBlock))) {
            discard();
        }
    }

    @Override
    protected void initDataTracker() {
    }

    @Override
    protected void readCustomDataFromNbt(NbtCompound nbt) {
    }

    @Override
    protected void writeCustomDataToNbt(NbtCompound nbt) {
    }

    @Override
    public net.minecraft.util.math.Vec3d getPassengerRidingPos(Entity passenger) {
        // Sit the passenger directly on the seat marker (no extra mount offset).
        return getPos();
    }

    @Override
    public boolean isPushable() {
        return false;
    }
}
