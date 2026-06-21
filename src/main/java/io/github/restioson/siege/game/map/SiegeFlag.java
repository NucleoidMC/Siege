package io.github.restioson.siege.game.map;

import io.github.restioson.siege.entity.SiegeKitStandEntity;
import io.github.restioson.siege.game.SiegeTeams;
import io.github.restioson.siege.game.active.SiegeCaptureLogic;
import io.github.restioson.siege.game.active.capturing.CapturingState;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.BossEvent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BannerBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import xyz.nucleoid.map_templates.BlockBounds;
import xyz.nucleoid.plasmid.api.game.common.team.GameTeam;

import java.util.*;

public final class SiegeFlag {
    public final String id;
    public final String name;
    public final BlockBounds bounds;
    // Whether the flag could _ever_ be captured during a gate
    public boolean capturable = true;
    public boolean pluralName = false;
    public List<BlockBounds> flagIndicatorBlocks;
    public List<SiegeKitStandEntity> kitStands;

    @Nullable
    public ItemStack icon;

    @Nullable
    public SiegeSpawn attackerRespawn;
    @Nullable
    public SiegeSpawn defenderRespawn;

    public List<SiegeGate> gates = new ArrayList<>();

    public GameTeam team;

    public CapturingState capturingState;
    public int captureProgressTicks;

    // The flags which must be captured before this flag can be captured
    public List<SiegeFlag> prerequisiteFlags = new ArrayList<>();
    public List<SiegeFlag> recapturePrerequisites = new ArrayList<>();

    public final ServerBossEvent captureBar = new ServerBossEvent(UUID.randomUUID(), Component.literal("Capturing"), BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.NOTCHED_10);
    private final Set<ServerPlayer> capturingPlayers = new ReferenceOpenHashSet<>();

    public SiegeFlag(String id, String name, GameTeam team, BlockBounds bounds) {
        this.id = id;
        this.name = name;
        this.team = team;
        this.bounds = bounds;
        this.kitStands = new ArrayList<>();
    }

    @Nullable
    public SiegeSpawn getRespawnFor(GameTeam team) {
        if (team == SiegeTeams.ATTACKERS) {
            return this.attackerRespawn;
        } else {
            return this.defenderRespawn;
        }
    }

    public String pastToBe() {
        if (this.pluralName) {
            return "have";
        } else {
            return "has";
        }
    }

    public String presentTobe() {
        if (this.pluralName) {
            return "are";
        } else {
            return "is";
        }
    }

    public List<SiegeFlag> getUnmetPrerequisites() {
        var unmet = new ArrayList<SiegeFlag>();

        var captureTeam = SiegeTeams.opposite(this.team);

        if (captureTeam == SiegeTeams.ATTACKERS) {
            for (SiegeFlag flag : this.prerequisiteFlags) {
                if (flag.team != captureTeam) {
                    unmet.add(flag);
                }
            }
        } else {
            for (SiegeFlag flag : this.recapturePrerequisites) {
                if (flag.team != captureTeam) {
                    unmet.add(flag);
                }
            }
        }

        return unmet;
    }

    public boolean incrementCapture(GameTeam team, int amount) {
        this.captureProgressTicks += amount;

        if (this.captureProgressTicks >= SiegeCaptureLogic.CAPTURE_TIME_TICKS) {
            this.captureProgressTicks = 0;
            this.team = team;
            this.capturingState = null;

            return true;
        }

        return false;
    }

    public boolean decrementCapture(int amount) {
        this.captureProgressTicks -= amount;

        if (this.captureProgressTicks <= 0) {
            this.captureProgressTicks = 0;
            this.capturingState = null;

            return true;
        }

        return false;
    }

    public void updateCapturingPlayers(Collection<ServerPlayer> players) {
        for (ServerPlayer player : players) {
            if (this.capturingPlayers.add(player)) {
                this.captureBar.addPlayer(player);
            }
        }

        this.capturingPlayers.removeIf(player -> {
            if (!players.contains(player)) {
                this.captureBar.removePlayer(player);
                return true;
            }
            return false;
        });
    }

    public float captureFraction() {
        return (float) this.captureProgressTicks / SiegeCaptureLogic.CAPTURE_TIME_TICKS;
    }

    public void updateCaptureBar() {
        if (this.capturingState != null) {
            this.captureBar.setVisible(true);
            this.captureBar.setName(this.capturingState.getTitle());
            this.captureBar.setProgress(this.captureFraction());
            this.captureBar.setColor(this.capturingState.getCaptureBarColorForTeam(SiegeTeams.opposite(this.team)));
        } else {
            this.captureBar.setVisible(false);
        }
    }

    public void closeCaptureBar() {
        this.captureBar.removeAllPlayers();
        this.captureBar.setVisible(false);
    }

    public boolean isFrontLine(long time) {
        CapturingState state = this.capturingState;
        return (state != null && state.isUnderAttack()) || (this.gateUnderAttack(time));
    }

    public boolean gateUnderAttack(long time) {
        return this.gates.stream().anyMatch(gate -> gate.underAttack(time));
    }

    public void setTeamBlocks(ServerLevel world, GameTeam captureTeam) {
        for (BlockBounds blockBounds : this.flagIndicatorBlocks) {
            for (BlockPos blockPos : blockBounds) {
                BlockState blockState = world.getBlockState(blockPos);
                Block block = blockState.getBlock();
                if (block == Blocks.WOOL.blue() || block == Blocks.WOOL.red()) {
                    world.setBlockAndUpdate(blockPos, Blocks.WOOL.pick(captureTeam.config().blockDyeColor()).defaultBlockState());
                }

                if (block == Blocks.WALL_BANNER.blue() || block == Blocks.WALL_BANNER.red()) {
                    BlockState newBlockState = Blocks.WALL_BANNER.pick(captureTeam.config().blockDyeColor()).defaultBlockState().setValue(WallBannerBlock.FACING, blockState.getValue(WallBannerBlock.FACING));
                    world.setBlockAndUpdate(blockPos, newBlockState);
                }

                if (block == Blocks.BANNER.blue() || block == Blocks.BANNER.red()) {
                    BlockState newBlockState = Blocks.BANNER.pick(captureTeam.config().blockDyeColor()).defaultBlockState().setValue(BannerBlock.ROTATION, blockState.getValue(BannerBlock.ROTATION));
                    world.setBlockAndUpdate(blockPos, newBlockState);
                }

                if (block == Blocks.CONCRETE.blue() || block == Blocks.CONCRETE.red()) {
                    world.setBlockAndUpdate(blockPos, Blocks.CONCRETE.pick(captureTeam.config().blockDyeColor()).defaultBlockState());
                }
            }

        }
    }

    public void spawnParticles(ServerLevel world, ParticleOptions effect) {
        for (int i = 0; i < 24; i++) {
            var pos = this.bounds.sampleBlock(world.getRandom());
            if (!world.isEmptyBlock(pos)) {
                continue;
            }

            world.sendParticles(effect, pos.getX(), pos.getY(), pos.getZ(), 1, 0.0D, 0.0D, 0.0D, 0);
        }
    }

    public void playSound(ServerLevel world, SoundEvent event, float pitch) {
        var centre = this.bounds.center();
        world.playSound(null, centre.x, centre.y, centre.z, event, SoundSource.NEUTRAL, 2.0f, pitch);
    }

    /**
     * Whether this _flag_ is under attack (not including gate)
     */
    public boolean isFlagUnderAttack() {
        return this.capturingState != null && this.capturingState.isUnderAttack();
    }
}
