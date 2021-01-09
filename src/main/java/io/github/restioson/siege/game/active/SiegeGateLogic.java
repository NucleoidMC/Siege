package io.github.restioson.siege.game.active;

import io.github.restioson.siege.Siege;
import io.github.restioson.siege.game.SiegeKit;
import io.github.restioson.siege.game.SiegeTeams;
import io.github.restioson.siege.game.map.SiegeGate;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.item.Items;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.LiteralText;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.explosion.Explosion;
import sun.awt.image.PixelConverter;
import xyz.nucleoid.plasmid.game.player.GameTeam;
import xyz.nucleoid.plasmid.util.PlayerRef;

import java.awt.*;
import java.util.Random;

public class SiegeGateLogic {
    private final SiegeActive active;

    public SiegeGateLogic(SiegeActive active) {
        this.active = active;
    }

    public void tick() {
        for (SiegeGate gate : this.active.map.gates) {
            this.tickGate(gate);
        }
    }

    public ActionResult maybeBraceGate(BlockPos pos, ServerPlayerEntity player, int slot, ItemUsageContext ctx) {
        for (SiegeGate gate : this.active.map.gates) {
            if (gate.brace != null && gate.brace.contains(pos)) {
                if (gate.health < gate.maxHealth) {
                    gate.health += 1;
                    player.sendMessage(new LiteralText("Gate health: ").append(Integer.toString(gate.health)).formatted(Formatting.DARK_GREEN), true);

                    this.active.gameSpace.getWorld().setBlockState(pos, Blocks.AIR.getDefaultState());
                    ctx.getStack().decrement(1);
                } else {
                    player.sendMessage(new LiteralText("The gate is already at max health!").formatted(Formatting.DARK_GREEN), true);
                }
                player.networkHandler.sendPacket(new ScreenHandlerSlotUpdateS2CPacket(-2, slot, ctx.getStack()));
                return ActionResult.FAIL;
            }
        }

        return ActionResult.PASS;
    }

    public ActionResult maybeBash(BlockPos pos, ServerPlayerEntity player, SiegePlayer participant) {
        Item mainHandItem = player.inventory.getMainHandStack().getItem();
        boolean holdingBashWeapon = mainHandItem == Items.IRON_SWORD || mainHandItem == Items.STONE_AXE;
        boolean rightKit = participant.kit == SiegeKit.SHIELD_BEARER || participant.kit == SiegeKit.SOLDIER;

        for (SiegeGate gate : this.active.map.gates) {
            if (!gate.bashedOpen && gate.health > 0 && gate.portcullis.contains(pos)) {
                if (participant.team == gate.flag.team) {
                    player.sendMessage(new LiteralText("You cannot bash your own gate!").formatted(Formatting.RED), true);
                    player.playSound(SoundEvents.ENTITY_VILLAGER_NO, SoundCategory.PLAYERS, 1f, 1f);
                    return ActionResult.FAIL;
                } else if (!rightKit) {
                    player.sendMessage(new LiteralText("Only soldiers and shieldbearers can bash!").formatted(Formatting.RED), true);
                    player.playSound(SoundEvents.ENTITY_VILLAGER_NO, SoundCategory.PLAYERS, 1f, 1f);
                    return ActionResult.FAIL;
                } else if (!holdingBashWeapon) {
                    player.sendMessage(new LiteralText("You can only bash with a sword or axe!").formatted(Formatting.RED), true);
                    player.playSound(SoundEvents.ENTITY_VILLAGER_NO, SoundCategory.PLAYERS, 1f, 1f);
                    return ActionResult.FAIL;
                } else if (!player.isSprinting()) {
                    player.sendMessage(new LiteralText("You must be sprinting to bash!").formatted(Formatting.RED), true);
                    player.playSound(SoundEvents.ENTITY_VILLAGER_NO, SoundCategory.PLAYERS, 1f, 1f);
                    return ActionResult.FAIL;
                } else if (player.getItemCooldownManager().isCoolingDown(mainHandItem)) {
                    return ActionResult.FAIL;
                }
                player.getItemCooldownManager().set(Items.IRON_SWORD, 20);
                player.getItemCooldownManager().set(Items.STONE_AXE, 20);
                ServerWorld world = this.active.gameSpace.getWorld();
                world.createExplosion(null, pos.getX(), pos.getY(), pos.getZ(), 0.0f, Explosion.DestructionType.NONE);
                gate.health -= 1;
                player.sendMessage(new LiteralText("Gate health: ").append(Integer.toString(gate.health)).formatted(Formatting.DARK_GREEN), true);
                return ActionResult.FAIL;
            }
        }

        return ActionResult.PASS;
    }

    public void tickGate(SiegeGate gate) {
        ServerWorld world = this.active.gameSpace.getWorld();

        if (gate.health == 0 && !gate.bashedOpen) {
            gate.slider.setOpen(world);

            BlockPos min = gate.portcullis.getMin();
            BlockPos max = gate.portcullis.getMax();
            Random rand = world.getRandom();

            for (int i = 0; i < 10; i++) {
                double x = min.getX() + rand.nextInt(max.getX() - min.getX() + 1);
                double y = min.getY() + rand.nextInt(max.getY() - min.getY() + 1);
                double z = min.getZ() + rand.nextInt(max.getZ() - min.getZ() + 1);

                world.createExplosion(null, x, y, z, 0.0f, Explosion.DestructionType.NONE);
            }

            GameTeam bashTeam;

            if (gate.flag.team == SiegeTeams.ATTACKERS) {
                bashTeam = SiegeTeams.DEFENDERS;
            } else {
                bashTeam = SiegeTeams.ATTACKERS;
            }

            final Vec3d boundsCenter = gate.portcullis.getCenter();

            Siege.spawnFirework(world, boundsCenter.x, boundsCenter.y + 12D, boundsCenter.z, new Color[]{
                    new Color(bashTeam.getColor()),
                    new Color(bashTeam.getFireworkColor())
            }, false, 0);

            this.active.gameSpace.getPlayers().sendMessage(
                    new LiteralText("The ")
                            .append(new LiteralText(gate.flag.name).formatted(Formatting.YELLOW))
                            .append(" ")
                            .append(gate.flag.pastToBe())
                            .append(" been bashed open by the ")
                            .append(new LiteralText(bashTeam.getDisplay()).formatted(bashTeam.getFormatting()))
                            .append("!")
                            .formatted(Formatting.BOLD)
            );

            this.active.gameSpace.getPlayers().sendSound(SoundEvents.EVENT_RAID_HORN, SoundCategory.PLAYERS, 1f, 1f);

            gate.bashedOpen = true;
        } else if (gate.health == gate.repairedHealthThreshold && gate.bashedOpen) {
            GameTeam team = gate.flag.team;
            this.active.gameSpace.getPlayers().sendMessage(
                    new LiteralText("The ")
                            .append(new LiteralText(gate.flag.name).formatted(Formatting.YELLOW))
                            .append(" ")
                            .append(gate.flag.pastToBe())
                            .append(" been repaired by the ")
                            .append(new LiteralText(team.getDisplay()).formatted(team.getFormatting()))
                            .append("!")
                            .formatted(Formatting.BOLD)
            );

            final Vec3d boundsCenter = gate.portcullis.getCenter();

            Siege.spawnFirework(world, boundsCenter.x, boundsCenter.y + 12D, boundsCenter.z, new Color[]{
                    new Color(team.getColor()),
                    new Color(team.getFireworkColor())
            }, false, 0);

            BlockPos max = gate.portcullis.getMax();
            world.playSound(null, max.getX(), max.getY(), max.getZ(), SoundEvents.BLOCK_ANVIL_USE, SoundCategory.BLOCKS, 1.0f, world.random.nextFloat() * 0.25F + 0.6F);

            this.active.gameSpace.getPlayers().sendSound(SoundEvents.ENTITY_VILLAGER_CELEBRATE, SoundCategory.PLAYERS, 1f, 1f);

            gate.slider.setClosed(world);
            gate.bashedOpen = false;
        }

        if (gate.bashedOpen) {
            return;
        }

        boolean ownerTeamPresent = false;
        boolean enemyTeamPresent = false;

        for (Object2ObjectMap.Entry<PlayerRef, SiegePlayer> entry : Object2ObjectMaps.fastIterable(this.active.participants)) {
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

        BlockPos pos = gate.portcullis.getMax();
        double x = pos.getX();
        double y = pos.getY();
        double z = pos.getZ();

        world.playSound(null, x, y, z, SoundEvents.BLOCK_LADDER_STEP, SoundCategory.BLOCKS, 1.0f, world.random.nextFloat() * 0.25F + 0.6F);
    }
}
