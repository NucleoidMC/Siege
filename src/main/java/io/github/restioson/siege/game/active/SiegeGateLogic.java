package io.github.restioson.siege.game.active;

import io.github.restioson.siege.game.SiegeKit;
import io.github.restioson.siege.game.SiegeTeams;
import io.github.restioson.siege.game.map.SiegeGate;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import net.minecraft.SharedConstants;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
import xyz.nucleoid.plasmid.game.common.team.GameTeam;
import xyz.nucleoid.plasmid.util.PlayerRef;

public class SiegeGateLogic {
    private final SiegeActive game;

    public SiegeGateLogic(SiegeActive game) {
        this.game = game;
    }

    public void tick() {
        for (SiegeGate gate : this.game.map.gates) {
            this.tickGate(gate);
        }
    }

    public ActionResult maybeBraceGate(BlockPos pos, ServerPlayerEntity player, ItemUsageContext ctx) {
        for (SiegeGate gate : this.game.map.gates) {
            if (gate.brace != null && gate.brace.contains(pos)) {
                if (gate.health < gate.maxHealth) {
                    ServerWorld world = this.game.world;
                    gate.health += 1;
                    gate.broadcastHealth(player, this.game, world);
                    world.setBlockState(pos, Blocks.AIR.getDefaultState());
                    ctx.getStack().decrement(1);
                    return ActionResult.FAIL;
                } else {
                    player.sendMessage(Text.literal("The gate is already at max health!").formatted(Formatting.DARK_GREEN), true);
                }
                return ActionResult.FAIL;
            }
        }

        return ActionResult.PASS;
    }

    public ActionResult maybeBash(BlockPos pos, ServerPlayerEntity player, SiegePlayer participant, long time) {
        Item mainHandItem = player.getInventory().getMainHandStack().getItem();
        boolean holdingBashWeapon = mainHandItem == Items.IRON_SWORD || mainHandItem == Items.STONE_AXE;
        boolean rightKit = participant.kit == SiegeKit.SHIELD_BEARER || participant.kit == SiegeKit.SOLDIER;

        for (SiegeGate gate : this.game.map.gates) {
            if (!gate.bashedOpen && gate.health > 0 && gate.portcullis.contains(pos)) {
                if (participant.team == gate.flag.team) {
                    player.sendMessage(Text.literal("You cannot bash your own gate!").formatted(Formatting.RED), true);
                    return ActionResult.FAIL;
                } else if (!rightKit) {
                    player.sendMessage(Text.literal("Only soldiers and shieldbearers can bash!").formatted(Formatting.RED), true);
                    return ActionResult.FAIL;
                } else if (!holdingBashWeapon) {
                    player.sendMessage(Text.literal("You can only bash with a sword or axe!").formatted(Formatting.RED), true);
                    return ActionResult.FAIL;
                } else if (!player.isSprinting()) {
                    player.sendMessage(Text.literal("You must be sprinting to bash!").formatted(Formatting.RED), true);
                    return ActionResult.FAIL;
                } else if (player.getItemCooldownManager().isCoolingDown(mainHandItem)) {
                    return ActionResult.FAIL;
                }

                player.getItemCooldownManager().set(Items.IRON_SWORD, SharedConstants.TICKS_PER_SECOND);
                player.getItemCooldownManager().set(Items.STONE_AXE, SharedConstants.TICKS_PER_SECOND);
                ServerWorld world = this.game.world;
                world.createExplosion(null, pos.getX(), pos.getY(), pos.getZ(), 0.0f, World.ExplosionSourceType.NONE);
                gate.health -= 1;
                gate.timeOfLastBash = time;
                gate.broadcastHealth(player, this.game, world);

                return ActionResult.FAIL;
            }
        }

        return ActionResult.PASS;
    }

    public void tickGate(SiegeGate gate) {
        ServerWorld world = this.game.world;

        if (gate.health <= 0 && !gate.bashedOpen) {
            gate.slider.setOpen(world);

            BlockPos min = gate.portcullis.min();
            BlockPos max = gate.portcullis.max();
            Random rand = world.getRandom();

            for (int i = 0; i < 10; i++) {
                double x = min.getX() + rand.nextInt(max.getX() - min.getX() + 1);
                double y = min.getY() + rand.nextInt(max.getY() - min.getY() + 1);
                double z = min.getZ() + rand.nextInt(max.getZ() - min.getZ() + 1);

                world.createExplosion(null, x, y, z, 0.0f, World.ExplosionSourceType.NONE);
            }

            GameTeam bashTeam = gate.flag.team == SiegeTeams.ATTACKERS ? SiegeTeams.DEFENDERS : SiegeTeams.ATTACKERS;

            this.game.gameSpace.getPlayers().sendMessage(
                    Text.literal("The ")
                            .append(Text.literal(gate.flag.name).formatted(Formatting.YELLOW))
                            .append(ScreenTexts.SPACE)
                            .append(gate.flag.pastToBe())
                            .append(" been bashed open by the ")
                            .append(bashTeam.config().name())
                            .append("!")
                            .formatted(Formatting.BOLD)
            );

            gate.bashedOpen = true;
        } else if (gate.health >= gate.repairedHealthThreshold && gate.bashedOpen) {
            GameTeam team = gate.flag.team;
            this.game.gameSpace.getPlayers().sendMessage(
                    Text.literal("The ")
                            .append(Text.literal(gate.flag.name).formatted(Formatting.YELLOW))
                            .append(ScreenTexts.SPACE)
                            .append(gate.flag.pastToBe())
                            .append(" been repaired by the ")
                            .append(team.config().name())
                            .append("!")
                            .formatted(Formatting.BOLD)
            );

            BlockPos max = gate.portcullis.max();
            world.playSound(null, max.getX(), max.getY(), max.getZ(), SoundEvents.BLOCK_ANVIL_USE, SoundCategory.BLOCKS, 1.0f, world.random.nextFloat() * 0.25F + 0.6F);

            gate.slider.setClosed(world);
            gate.bashedOpen = false;
        }

        if (gate.bashedOpen) {
            return;
        }

        boolean ownerTeamPresent = false;
        boolean enemyTeamPresent = false;

        for (Object2ObjectMap.Entry<PlayerRef, SiegePlayer> entry : Object2ObjectMaps.fastIterable(this.game.participants)) {
            ServerPlayerEntity player = entry.getKey().getEntity(world);
            if (player == null || player.interactionManager.getGameMode() != GameMode.SURVIVAL) {
                continue;
            }

            if (gate.gateOpen.contains(player.getBlockPos())) {
                SiegePlayer participant = entry.getValue();
                if (participant.team == gate.flag.team) {
                    ownerTeamPresent = true;
                } else {
                    enemyTeamPresent = true;
                }
            }
        }

        boolean shouldOpen = ownerTeamPresent && !enemyTeamPresent;

        boolean moved = shouldOpen ? gate.tickOpen(world) : gate.tickClose(world);
        if (!moved) {
            return;
        }

        BlockPos pos = gate.portcullis.max();
        double x = pos.getX();
        double y = pos.getY();
        double z = pos.getZ();

        world.playSound(null, x, y, z, SoundEvents.BLOCK_LADDER_STEP, SoundCategory.BLOCKS, 1.0f, world.random.nextFloat() * 0.25F + 0.6F);
    }
}
