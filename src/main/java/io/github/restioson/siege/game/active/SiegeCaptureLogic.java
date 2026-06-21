package io.github.restioson.siege.game.active;

import io.github.restioson.siege.entity.SiegeKitStandEntity;
import io.github.restioson.siege.game.SiegeSpawnLogic;
import io.github.restioson.siege.game.SiegeTeams;
import io.github.restioson.siege.game.active.capturing.CapturingState;
import io.github.restioson.siege.game.map.SiegeFlag;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.world.entity.EntityTypes;
import xyz.nucleoid.plasmid.api.game.GameSpace;
import xyz.nucleoid.plasmid.api.game.common.team.GameTeam;
import xyz.nucleoid.plasmid.api.util.PlayerRef;
import xyz.nucleoid.plasmid.api.util.PlayerUtil;
import xyz.nucleoid.plasmid.api.util.Scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.ChatFormatting;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;

public final class SiegeCaptureLogic {
    public static final int CAPTURE_TIME_TICKS = 20 * 40;

    private final ServerLevel world;
    private final GameSpace gameSpace;
    private final SiegeActive game;

    private final List<ServerPlayer> defendersPresent = new ArrayList<>();
    private final List<ServerPlayer> attackersPresent = new ArrayList<>();
    private final Set<ServerPlayer> playersPresent = new ReferenceOpenHashSet<>();

    SiegeCaptureLogic(SiegeActive game) {
        this.world = game.world;
        this.gameSpace = game.gameSpace;
        this.game = game;
    }

    void tick(ServerLevel world, int interval) {
        for (SiegeFlag flag : this.game.map.flags) {
            if (flag.capturable) {
                this.tickCaptureFlag(world, flag, interval);
            }
        }
    }

    private void tickCaptureFlag(ServerLevel world, SiegeFlag flag, int interval) {
        List<ServerPlayer> defendersPresent = this.defendersPresent;
        List<ServerPlayer> attackersPresent = this.attackersPresent;
        Set<ServerPlayer> playersPresent = this.playersPresent;

        defendersPresent.clear();
        attackersPresent.clear();
        playersPresent.clear();

        for (Object2ObjectMap.Entry<PlayerRef, SiegePlayer> entry : Object2ObjectMaps.fastIterable(this.game.participants)) {
            ServerPlayer player = entry.getKey().getEntity(world);
            if (player == null) {
                continue;
            }

            if (player.gameMode.getGameModeForPlayer() != GameType.SURVIVAL) {
                continue;
            }

            SiegePlayer participant = entry.getValue();

            if (flag.bounds.contains(player.blockPosition())) {
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

        boolean recapture = this.game.config.recapture();
        boolean attackersAtFlag = !attackersPresent.isEmpty();
        boolean defendersAtFlag = !defendersPresent.isEmpty();
        var prereqs = flag.getUnmetPrerequisites();
        CapturingState state;

        if (defendersAtFlag && flag.team != SiegeTeams.DEFENDERS && !recapture) {
            // Defenders _would_ capture/contest, but it's disabled
            state = CapturingState.recaptureDisabled();
        } else if (defendersAtFlag && attackersAtFlag) {
            // Both teams present - contested
            state = prereqs.isEmpty() ? CapturingState.contested() : CapturingState.prerequisitesRequired(prereqs);
        } else if ((attackersAtFlag && flag.team != SiegeTeams.ATTACKERS) ||
                (defendersAtFlag && flag.team != SiegeTeams.DEFENDERS)) {
            // Only one team present - capturing if prerequisites met
            state = prereqs.isEmpty() ? CapturingState.capturing() : CapturingState.prerequisitesRequired(prereqs);
        } else {
            // Only owner team (or no players) present
            state = flag.captureProgressTicks > 0 ? CapturingState.securing() : null;
        }

        flag.capturingState = state;

        if (state == CapturingState.capturing()) {
            var team = attackersAtFlag ? SiegeTeams.ATTACKERS : SiegeTeams.DEFENDERS;
            this.tickCapturing(flag, interval, team, playersPresent);
        } else if (state == CapturingState.securing()) {
            this.tickSecuring(flag, interval, playersPresent);
        } else if (state == CapturingState.contested()) {
            this.tickContested(flag);
        }

        flag.updateCaptureBar();
        flag.updateCapturingPlayers(playersPresent);
    }

    private void tickCapturing(SiegeFlag flag, int interval, GameTeam captureTeam,
                               Set<ServerPlayer> capturingPlayers) {
        // Just began capturing
        if (flag.captureProgressTicks == 0) {
            this.broadcastStartCapture(flag, captureTeam);
        }

        if (flag.incrementCapture(captureTeam, interval * capturingPlayers.size())) {
            for (SiegeKitStandEntity kitStand : flag.kitStands) {
                kitStand.onControllingFlagCaptured();
            }

            for (ServerPlayer player : capturingPlayers) {
                SiegePlayer participant = this.game.participant(player);
                if (participant != null) {
                    participant.captures += 1;
                }
            }

            this.broadcastCaptured(flag, captureTeam);
            flag.setTeamBlocks(this.game.world, captureTeam);
            this.game.stageManager.addTime(this.game.config.capturingGiveTimeSecs());

            for (ServerPlayer player : capturingPlayers) {
                PlayerUtil.playSoundToPlayer(player, SoundEvents.PLAYER_LEVELUP, SoundSource.NEUTRAL, 1.0F, 1.0F);
            }

            Component sub = null;
            if (this.game.config.capturingGiveTimeSecs() > 0) {
                sub = Component.empty()
                        .append(SiegeTeams.ATTACKERS.config().name())
                        .append(CommonComponents.SPACE)
                        .append(Component.translatable("game.siege.flag.captured.extra_time.1"))
                        .append(CommonComponents.SPACE)
                        .append(Component.literal(this.game.config.giveTimeFormatted()).withStyle(ChatFormatting.AQUA))
                        .append(CommonComponents.SPACE)
                        .append(Component.translatable("game.siege.flag.captured.extra_time.2"));
            }

            this.game.showTitle(
                    captureTeam,
                    Component.translatable("game.siege.flag.captured.won", flag.name).withStyle(ChatFormatting.GREEN),
                    sub
            );

            this.game.showTitle(
                    SiegeTeams.opposite(captureTeam),
                    Component.translatable("game.siege.flag.captured.lost", flag.name).withStyle(ChatFormatting.RED),
                    sub
            );
        } else {
            flag.playSound(this.world, SoundEvents.NOTE_BLOCK_BASS.value(), 1.0F + flag.captureFraction());
        }

        var particles = captureTeam == SiegeTeams.ATTACKERS ? ParticleTypes.FLAME : ParticleTypes.SOUL_FIRE_FLAME;
        flag.spawnParticles(this.world, particles);
    }

    private void tickContested(SiegeFlag flag) {
        flag.playSound(this.world, SoundEvents.NOTE_BLOCK_DIDGERIDOO.value(), 1.0F);
        flag.spawnParticles(this.world, ParticleTypes.ANGRY_VILLAGER);
    }

    private void tickSecuring(SiegeFlag flag, int interval, Set<ServerPlayer> securingPlayers) {
        if (flag.decrementCapture(interval * (securingPlayers.size() + 1))) {
            this.broadcastSecured(flag);

            for (ServerPlayer player : securingPlayers) {
                SiegePlayer participant = this.game.participant(player);
                if (participant != null) {
                    participant.secures += 1;
                }
            }
        }

        flag.playSound(this.world, SoundEvents.NOTE_BLOCK_CHIME.value(), 1.0F + flag.captureFraction());
        flag.spawnParticles(this.world, ParticleTypes.COMPOSTER);
    }

    private void broadcastStartCapture(SiegeFlag flag, GameTeam captureTeam) {
        var capture = captureTeam == SiegeTeams.ATTACKERS ? "captured" : "recaptured";

        this.gameSpace.getPlayers().sendMessage(
                Component.literal("The ")
                        .append(Component.literal(flag.name).withStyle(ChatFormatting.YELLOW))
                        .append(CommonComponents.SPACE)
                        .append(flag.presentTobe())
                        .append(" being ")
                        .append(capture)
                        .append(" by the ")
                        .append(captureTeam.config().name())
                        .append("...")
                        .withStyle(ChatFormatting.BOLD)
        );

        this.gameSpace.getPlayers().playSound(SoundEvents.BELL_BLOCK);

        for (Object2ObjectMap.Entry<PlayerRef, SiegePlayer> entry : Object2ObjectMaps.fastIterable(this.game.participants)) {
            if (entry.getValue().team == captureTeam) {
                continue;
            }

            entry.getKey().ifOnline(
                    this.world,
                    player -> {
                        AtomicInteger plays = new AtomicInteger();
                        Scheduler.INSTANCE.repeatWhile(
                                s -> PlayerUtil.playSoundToPlayer(player, SoundEvents.BELL_BLOCK, SoundSource.PLAYERS, 1.0f, 1.0f),
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
                Component.literal("The ")
                        .append(Component.literal(flag.name).withStyle(ChatFormatting.YELLOW))
                        .append(CommonComponents.SPACE)
                        .append(flag.pastToBe())
                        .append(" been captured by the ")
                        .append(captureTeam.config().name())
                        .append("!")
                        .withStyle(ChatFormatting.BOLD)
        );

        Vec3 pos = SiegeSpawnLogic.choosePos(this.world.getRandom(), flag.bounds, 0.0f);
        LightningBolt lightningEntity = EntityTypes.LIGHTNING_BOLT.create(this.world, EntitySpawnReason.TRIGGERED);
        Objects.requireNonNull(lightningEntity).snapTo(pos);
        lightningEntity.setVisualOnly(true);
        this.world.addFreshEntity(lightningEntity);
    }

    private void broadcastSecured(SiegeFlag flag) {
        this.gameSpace.getPlayers().sendMessage(
                Component.literal("The ")
                        .append(Component.literal(flag.name).withStyle(ChatFormatting.YELLOW))
                        .append(CommonComponents.SPACE)
                        .append(flag.pastToBe())
                        .append(" been defended by the ")
                        .append(flag.team.config().name())
                        .append("!")
                        .withStyle(ChatFormatting.BOLD)
        );
    }
}
