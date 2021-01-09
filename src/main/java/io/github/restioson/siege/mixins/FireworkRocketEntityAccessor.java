package io.github.restioson.siege.mixins;

import net.minecraft.entity.projectile.FireworkRocketEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(FireworkRocketEntity.class)
public interface FireworkRocketEntityAccessor
{
    @Accessor("lifeTime")
    int getLifeTime();

    @Accessor("lifeTime")
    void setLifeTime(int lifeTime);
}
