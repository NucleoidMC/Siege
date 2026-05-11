package io.github.restioson.siege.mixin;

import io.github.restioson.siege.duck.SiegeVehicleExt;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.vehicle.VehicleEntity;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VehicleEntity.class)
public class VehicleEntityMixin implements SiegeVehicleExt {
    @Unique
    public boolean siege$inSiegeGame;

    @Inject(method = "destroy(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/item/Item;)V", at = @At("HEAD"), cancellable = true)
    void killAndDropItem(ServerLevel world, Item item, CallbackInfo ci) {
        if (this.siege$inSiegeGame) {
            ci.cancel();
        }
    }

    @Unique
    @Override
    public void siege$setInSiegeGame() {
        this.siege$inSiegeGame = true;
    }
}
