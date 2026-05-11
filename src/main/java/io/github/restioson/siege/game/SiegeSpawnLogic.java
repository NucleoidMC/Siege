package io.github.restioson.siege.game;

import io.github.restioson.siege.game.map.SiegeSpawn;
import xyz.nucleoid.map_templates.BlockBounds;
import xyz.nucleoid.plasmid.api.game.player.JoinAcceptor;
import xyz.nucleoid.plasmid.api.game.player.JoinAcceptorResult;

import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.ProblemReporter;
import net.minecraft.util.RandomSource;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

public class SiegeSpawnLogic {
    public static JoinAcceptorResult.Teleport acceptPlayer(JoinAcceptor offer, ServerLevel world, SiegeSpawn spawn, GameType gameMode) {
        var pos = SiegeSpawnLogic.choosePos(world.getRandom(), spawn.bounds(), 0.5F);
        return offer.teleport(world, pos)
                .thenRunForEach((player) -> {
                    player.setYRot(spawn.yaw());
                    resetPlayer(player, gameMode);
                });
    }

    public static void resetPlayer(ServerPlayer player, GameType gameMode) {
        player.setGameMode(gameMode);
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0.0f;
        player.removeAllEffects();
        player.setRemainingFireTicks(0);
        resetHunger(player);
    }

    private static void resetHunger(ServerPlayer player) {
        var resetTag = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, player.registryAccess());
        FoodData hungerManager = new FoodData();
        hungerManager.addAdditionalSaveData(resetTag);
        player.getFoodData().readAdditionalSaveData(TagValueInput.create(ProblemReporter.DISCARDING, player.registryAccess(), resetTag.buildResult()));
    }

    public static Vec3 choosePos(RandomSource random, BlockBounds bounds, float aboveGround) {
        BlockPos min = bounds.min();
        BlockPos max = bounds.max();

        double x = Mth.nextDouble(random, min.getX(), max.getX());
        double z = Mth.nextDouble(random, min.getZ(), max.getZ());
        double y = min.getY() + aboveGround;

        return new Vec3(x, y, z);
    }

    public static void spawnPlayer(ServerPlayer player, SiegeSpawn spawn, ServerLevel world) {
        Vec3 pos = SiegeSpawnLogic.choosePos(player.getRandom(), spawn.bounds(), 0.5f);
        player.teleportTo(world, pos.x, pos.y, pos.z, Set.of(), spawn.yaw(), 0.0F, false);
    }
}
