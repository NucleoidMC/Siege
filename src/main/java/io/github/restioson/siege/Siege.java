package io.github.restioson.siege;

import io.github.restioson.siege.game.SiegeConfig;
import io.github.restioson.siege.game.SiegeWaiting;
import io.github.restioson.siege.mixins.FireworkRocketEntityAccessor;
import net.fabricmc.api.ModInitializer;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import xyz.nucleoid.plasmid.game.GameType;

import java.awt.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class Siege implements ModInitializer {
    public static final String ID = "siege";
    public static final Logger LOGGER = LogManager.getLogger(ID);

    @Override
    public void onInitialize() {
        GameType.register(
                new Identifier(ID, "siege"),
                SiegeWaiting::open,
                SiegeConfig.CODEC
        );
    }

    public static void spawnFirework(@NotNull ServerWorld world, double x, double y, double z, Color[] colors, boolean silent, int lifetime)
    {
        ItemStack fireworkStack = new ItemStack(Items.FIREWORK_ROCKET);

        CompoundTag tag = fireworkStack.getOrCreateSubTag("Fireworks");
        tag.putByte("Flight", (byte) 0);

        ListTag explosions = new ListTag();
        CompoundTag explosion = new CompoundTag();
        explosion.putByte("Type", (byte) 1);

        ArrayList<Integer> colorsAsInt = new ArrayList<>();

        for (Color color : colors) {
            colorsAsInt.add(color.getRGB());
        }

        explosion.putIntArray("Colors", colorsAsInt.stream().mapToInt(i->i).toArray());
        explosions.add(explosion);
        tag.put("Explosions", explosions);

        FireworkRocketEntity firework = new FireworkRocketEntity(world, x, y, z, fireworkStack);
        firework.setSilent(silent);
        if (lifetime >= 0)
            ((FireworkRocketEntityAccessor) firework).setLifeTime(lifetime);
        world.spawnEntity(firework);
    }
}
