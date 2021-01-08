package io.github.restioson.siege.game.active;

import com.google.common.collect.ImmutableList;
import io.github.restioson.siege.entity.SiegeKitStandEntity;
import io.github.restioson.siege.game.SiegeSpawnLogic;
import io.github.restioson.siege.game.SiegeTeams;
import io.github.restioson.siege.game.map.SiegeFlag;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.block.*;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LightningEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.LiteralText;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import xyz.nucleoid.plasmid.game.GameSpace;
import xyz.nucleoid.plasmid.game.player.GameTeam;
import xyz.nucleoid.plasmid.util.BlockBounds;
import xyz.nucleoid.plasmid.util.PlayerRef;
import xyz.nucleoid.plasmid.util.Scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public final class SiegeCaptureLogic {
    public static final int CAPTURE_TIME_TICKS = 20 * 40;

    private final GameSpace gameSpace;
    private final SiegeActive game;

    private final List<ServerPlayerEntity> defendersPresent = new ArrayList<>();
    private final List<ServerPlayerEntity> attackersPresent = new ArrayList<>();
    private final Set<ServerPlayerEntity> playersPresent = new ReferenceOpenHashSet<>();

    SiegeCaptureLogic(SiegeActive game) {
        this.gameSpace = game.gameSpace;
        this.game = game;
    }

    void tick(ServerWorld world, int interval) {
        for (SiegeFlag flag : this.game.map.flags) {
            if (flag.capturable) {
                this.tickCaptureFlag(world, flag, interval);
            }
        }
    }

    private void tickCaptureFlag(ServerWorld world, SiegeFlag flag, int interval) {
        List<ServerPlayerEntity> defendersPresent = this.defendersPresent;
        List<ServerPlayerEntity> attackersPresent = this.attackersPresent;
        Set<ServerPlayerEntity> playersPresent = this.playersPresent;

        defendersPresent.clear();
        attackersPresent.clear();
        playersPresent.clear();

        for (Object2ObjectMap.Entry<PlayerRef, SiegePlayer> entry : Object2ObjectMaps.fastIterable(this.game.participants)) {
            ServerPlayerEntity player = entry.getKey().getEntity(world);
            if (player == null) {
                continue;
            }

            if (player.interactionManager.getGameMode() != GameMode.SURVIVAL) {
                continue;
            }

            SiegePlayer participant = entry.getValue();

            if (flag.bounds.contains(player.getBlockPos())) {
                GameTeam team = participant.team;
                if (team == SiegeTeams.DEFENDERS) {
                    defendersPresent.add(player);
                } else if (team == SiegeTeams.ATTACKERS) {
                    attackersPresent.add(player);
                }
            }
        }

        playersPresent.addAll(attackersPresent);
        playersPresent.addAll(defendersPresent);

        boolean recapture = this.game.config.recapture;
        boolean defendersAtFlag = !defendersPresent.isEmpty();
        boolean defendersActuallyCapturing = defendersAtFlag && recapture;
        boolean attackersCapturing = !attackersPresent.isEmpty();
        boolean contested = defendersAtFlag && attackersCapturing && !(flag.team == SiegeTeams.ATTACKERS && recapture);
        boolean capturing = defendersActuallyCapturing || attackersCapturing;

        CapturingState capturingState = null;
        GameTeam captureTeam = flag.team;
        List<ServerPlayerEntity> capturingPlayers = ImmutableList.of();

        if (capturing) {
            if (!contested) {
                if (defendersAtFlag) {
                    captureTeam = SiegeTeams.DEFENDERS;
                    capturingPlayers = defendersPresent;
                } else {
                    captureTeam = SiegeTeams.ATTACKERS;
                    capturingPlayers = attackersPresent;
                }

                capturingState = captureTeam != flag.team ? CapturingState.CAPTURING : null;
            } else {
                capturingState = CapturingState.CONTESTED;
            }
        } else {
            if (flag.captureProgressTicks > 0) {
                capturingState = CapturingState.SECURING;
            }
        }

        if (capturingState != null && !flag.isReadyForCapture()) {
            capturingState = CapturingState.PREREQUISITE_REQUIRED;
        }

        flag.capturingState = capturingState;

        if (capturingState == CapturingState.CAPTURING) {
            this.tickCapturing(flag, interval, captureTeam, capturingPlayers);
        } else if (capturingState == CapturingState.SECURING) {
            this.tickSecuring(flag, interval);
        }

        flag.updateCaptureBar();
        flag.updateCapturingPlayers(playersPresent);
    }

    private void tickCapturing(SiegeFlag flag, int interval, GameTeam captureTeam, List<ServerPlayerEntity> capturingPlayers) {
        // Just began capturing
        if (flag.captureProgressTicks == 0) {
            this.broadcastStartCapture(flag, captureTeam);
        }

        if (flag.incrementCapture(captureTeam, interval * capturingPlayers.size())) {
            for (SiegeKitStandEntity kitStand : flag.kitStands) {
                kitStand.onControllingFlagCaptured();
            }

            this.broadcastCaptured(flag, captureTeam);

            ServerWorld world = this.game.gameSpace.getWorld();

            for (BlockBounds blockBounds : flag.flagIndicatorBlocks) {
                for (BlockPos blockPos : blockBounds) {
                    BlockState blockState = world.getBlockState(blockPos);
                    Block block = blockState.getBlock();
                    if (block == Blocks.BLUE_WOOL || block == Blocks.RED_WOOL) {
                        Block wool;

                        if (captureTeam == SiegeTeams.DEFENDERS) {
                            wool = Blocks.BLUE_WOOL;
                        } else {
                            wool = Blocks.RED_WOOL;
                        }

                        world.setBlockState(blockPos, wool.getDefaultState());
                    }

                    if (block == Blocks.BLUE_WALL_BANNER || block == Blocks.RED_WALL_BANNER) {
                        Block banner;

                        if (captureTeam == SiegeTeams.DEFENDERS) {
                            banner = Blocks.BLUE_WALL_BANNER;
                        } else {
                            banner = Blocks.RED_WALL_BANNER;
                        }

                        BlockState newBlockState = banner.getDefaultState().with(WallBannerBlock.FACING, blockState.get(WallBannerBlock.FACING));
                        world.setBlockState(blockPos, newBlockState);
                    }

                    if (block == Blocks.BLUE_BANNER || block == Blocks.RED_BANNER) {
                        Block banner;

                        if (captureTeam == SiegeTeams.DEFENDERS) {
                            banner = Blocks.BLUE_BANNER;
                        } else {
                            banner = Blocks.RED_BANNER;
                        }

                        BlockState newBlockState = banner.getDefaultState().with(BannerBlock.ROTATION, blockState.get(BannerBlock.ROTATION));
                        world.setBlockState(blockPos, newBlockState);
                    }

                    if (block == Blocks.BLUE_CONCRETE || block == Blocks.RED_CONCRETE) {
                        Block concrete;

                        if (captureTeam == SiegeTeams.DEFENDERS) {
                            concrete = Blocks.BLUE_CONCRETE;
                        } else {
                            concrete = Blocks.RED_CONCRETE;
                        }

                        BlockState newBlockState = concrete.getDefaultState();
                        world.setBlockState(blockPos, newBlockState);
                    }
                }
            }

            for (ServerPlayerEntity player : capturingPlayers) {
                player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.NEUTRAL, 1.0F, 1.0F);
            }
        } else {
            for (ServerPlayerEntity player : capturingPlayers) {
                player.playSound(SoundEvents.BLOCK_STONE_PLACE,  SoundCategory.NEUTRAL, 1.0F, 1.0F);
            }
        }
    }

    private void tickSecuring(SiegeFlag flag, int interval) {
        if (flag.decrementCapture(interval)) {
            this.broadcastSecured(flag);
        }
    }

    private void broadcastStartCapture(SiegeFlag flag, GameTeam captureTeam) {
        this.gameSpace.getPlayers().sendMessage(
                new LiteralText("The ")
                        .append(new LiteralText(flag.name).formatted(Formatting.YELLOW))
                        .append(" ")
                        .append(flag.presentTobe())
                        .append(" being captured by the ")
                        .append(new LiteralText(captureTeam.getDisplay()).formatted(captureTeam.getFormatting()))
                        .append("...")
                        .formatted(Formatting.BOLD)
        );

        this.gameSpace.getPlayers().sendSound(SoundEvents.BLOCK_BELL_USE);

        for (Object2ObjectMap.Entry<PlayerRef, SiegePlayer> entry : Object2ObjectMaps.fastIterable(this.game.participants)) {
            if (entry.getValue().team == captureTeam) {
                continue;
            }

            entry.getKey().ifOnline(
                    this.gameSpace.getWorld(),
                    player -> {
                        AtomicInteger plays = new AtomicInteger();
                        Scheduler.INSTANCE.repeatWhile(
                                s -> player.playSound(SoundEvents.BLOCK_BELL_USE, SoundCategory.PLAYERS, 1.0f, 1.0f),
                                t -> plays.incrementAndGet() < 3,
                                0,
                                7
                        );
                    }
            );
        }
    }

    private void broadcastCaptured(SiegeFlag flag, GameTeam captureTeam) {
        this.gameSpace.getPlayers().sendMessage(
                new LiteralText("The ")
                        .append(new LiteralText(flag.name).formatted(Formatting.YELLOW))
                        .append(" ")
                        .append(flag.pastToBe())
                        .append(" been captured by the ")
                        .append(new LiteralText(captureTeam.getDisplay()).formatted(captureTeam.getFormatting()))
                        .append("!")
                        .formatted(Formatting.BOLD)
        );

        ServerWorld world = this.gameSpace.getWorld();

        Vec3d pos = SiegeSpawnLogic.choosePos(world.getRandom(), flag.bounds, 0.0f);
        LightningEntity lightningEntity = EntityType.LIGHTNING_BOLT.create(world);
        Objects.requireNonNull(lightningEntity).refreshPositionAfterTeleport(pos);
        lightningEntity.setCosmetic(true);
        world.spawnEntity(lightningEntity);
    }

    private void broadcastSecured(SiegeFlag flag) {
        this.gameSpace.getPlayers().sendMessage(
                new LiteralText("The ")
                        .append(new LiteralText(flag.name).formatted(Formatting.YELLOW))
                        .append(" ")
                        .append(flag.pastToBe())
                        .append(" been defended by the ")
                        .append(new LiteralText(flag.team.getDisplay()).formatted(flag.team.getFormatting()))
                        .append("!")
                        .formatted(Formatting.BOLD)
        );
    }
}
