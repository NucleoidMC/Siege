package io.github.restioson.siege.game;

import io.github.restioson.siege.game.map.SiegeSpawn;
import net.minecraft.entity.player.HungerManager;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.NbtReadView;
import net.minecraft.storage.NbtWriteView;
import net.minecraft.util.ErrorReporter;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.GameMode;
import xyz.nucleoid.map_templates.BlockBounds;
import xyz.nucleoid.plasmid.api.game.player.JoinAcceptor;
import xyz.nucleoid.plasmid.api.game.player.JoinAcceptorResult;

import java.util.Set;

public class SiegeSpawnLogic {
    public static JoinAcceptorResult.Teleport acceptPlayer(JoinAcceptor offer, ServerWorld world, SiegeSpawn spawn, GameMode gameMode) {
        var pos = SiegeSpawnLogic.choosePos(world.getRandom(), spawn.bounds(), 0.5F);
        return offer.teleport(world, pos)
                .thenRunForEach((player) -> {
                    player.setYaw(spawn.yaw());
                    resetPlayer(player, gameMode);
                });
    }

    public static void resetPlayer(ServerPlayerEntity player, GameMode gameMode) {
        player.changeGameMode(gameMode);
        player.setVelocity(Vec3d.ZERO);
        player.fallDistance = 0.0f;
        player.clearStatusEffects();
        player.setFireTicks(0);
        resetHunger(player);
    }

    private static void resetHunger(ServerPlayerEntity player) {
        var resetTag = NbtWriteView.create(ErrorReporter.EMPTY, player.getRegistryManager());
        HungerManager hungerManager = new HungerManager();
        hungerManager.writeData(resetTag);
        player.getHungerManager().readData(NbtReadView.create(ErrorReporter.EMPTY, player.getRegistryManager(), resetTag.getNbt()));
    }

    public static Vec3d choosePos(Random random, BlockBounds bounds, float aboveGround) {
        BlockPos min = bounds.min();
        BlockPos max = bounds.max();

        double x = MathHelper.nextDouble(random, min.getX(), max.getX());
        double z = MathHelper.nextDouble(random, min.getZ(), max.getZ());
        double y = min.getY() + aboveGround;

        return new Vec3d(x, y, z);
    }

    public static void spawnPlayer(ServerPlayerEntity player, SiegeSpawn spawn, ServerWorld world) {
        Vec3d pos = SiegeSpawnLogic.choosePos(player.getRandom(), spawn.bounds(), 0.5f);
        player.teleport(world, pos.x, pos.y, pos.z, Set.of(), spawn.yaw(), 0.0F, false);
    }
}
