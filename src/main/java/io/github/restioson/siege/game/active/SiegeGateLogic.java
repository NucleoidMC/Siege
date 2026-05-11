package io.github.restioson.siege.game.active;

import io.github.restioson.siege.game.SiegeKit;
import io.github.restioson.siege.game.SiegeTeams;
import io.github.restioson.siege.game.map.SiegeGate;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import net.minecraft.ChatFormatting;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.ItemTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import xyz.nucleoid.plasmid.api.game.common.team.GameTeam;
import xyz.nucleoid.plasmid.api.game.player.MutablePlayerSet;
import xyz.nucleoid.plasmid.api.util.PlayerRef;
import xyz.nucleoid.plasmid.api.util.Scheduler;
import xyz.nucleoid.stimuli.event.EventResult;

import java.util.List;
import java.util.function.Consumer;

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

    public EventResult maybeBraceGate(BlockPos pos, SiegePlayer participant, ServerPlayer player,
                                      UseOnContext ctx, long time) {
        for (SiegeGate gate : this.game.map.gates) {
            if (gate.brace != null && gate.brace.contains(pos)) {
                if (gate.health < gate.maxHealth) {
                    ServerLevel world = this.game.world;
                    gate.health += 1;
                    gate.broadcastHealth(player, this.game, world);
                    world.setBlockAndUpdate(pos, Blocks.AIR.defaultBlockState());
                    world.playSound(
                            player,
                            pos.getX(),
                            pos.getY(),
                            pos.getZ(),
                            SoundEvents.IRON_GOLEM_REPAIR,
                            SoundSource.BLOCKS,
                            1.0F,
                            1.0F + gate.repairFraction()
                    );
                    ctx.getItemInHand().shrink(1);
                    participant.timeOfLastBrace = time;
                    return EventResult.DENY;
                } else {
                    player.sendSystemMessage(Component.literal("The gate is already at max health!").withStyle(ChatFormatting.DARK_GREEN), true);
                }
                return EventResult.DENY;
            }
        }

        return EventResult.PASS;
    }

    public static boolean canUseToBash(Item item) {
        return item.builtInRegistryHolder().is(ItemTags.SWORDS) || item.builtInRegistryHolder().is(ItemTags.SHOVELS);
    }

    public EventResult maybeBash(BlockPos pos, ServerPlayer player, SiegePlayer participant, long time) {
        var mainHandItem = player.getMainHandItem();
        boolean rightKit = participant.kit == SiegeKit.SHIELD_BEARER || participant.kit == SiegeKit.SOLDIER;

        for (SiegeGate gate : this.game.map.gates) {
            if (!gate.bashedOpen && gate.health > 0 && gate.portcullis.contains(pos)) {
                var cooldownMgr = player.getCooldowns();

                if (participant.team == gate.flag.team) {
                    player.sendSystemMessage(Component.literal("You cannot bash your own gate!").withStyle(ChatFormatting.RED), true);
                    return EventResult.DENY;
                } else if (!rightKit) {
                    player.sendSystemMessage(Component.literal("Only soldiers and shieldbearers can bash!").withStyle(ChatFormatting.RED), true);
                    return EventResult.DENY;
                } else if (!canUseToBash(mainHandItem.getItem())) {
                    player.sendSystemMessage(Component.literal("You can only bash with a sword or axe!").withStyle(ChatFormatting.RED), true);
                    return EventResult.DENY;
                } else if (!player.isSprinting()) {
                    player.sendSystemMessage(Component.literal("You must be sprinting to bash!").withStyle(ChatFormatting.RED), true);
                    return EventResult.DENY;
                } else if (cooldownMgr.isOnCooldown(mainHandItem)) {
                    return EventResult.DENY;
                }

                var inventory = player.getInventory();
                for (var stack : inventory.getNonEquipmentItems()) {
                    if (canUseToBash(stack.getItem())) {
                        cooldownMgr.addCooldown(stack, SharedConstants.TICKS_PER_SECOND);
                    }
                }
                if (canUseToBash(player.getOffhandItem().getItem())) {
                    cooldownMgr.addCooldown(player.getOffhandItem(), SharedConstants.TICKS_PER_SECOND);
                }

                ServerLevel world = this.game.world;
                world.explode(null, pos.getX(), pos.getY(), pos.getZ(), 0.0f, Level.ExplosionInteraction.NONE);
                gate.health -= 1;
                gate.timeOfLastBash = time;
                gate.broadcastHealth(player, this.game, world);

                return EventResult.DENY;
            }
        }

        return EventResult.PASS;
    }

    public void tickGate(SiegeGate gate) {
        ServerLevel world = this.game.world;

        long time = world.getGameTime();

        if (gate.underAttack(time)) {
            this.game.team(gate.flag.team)
                    .sendActionBar(Component.translatable("game.siege.gate.under_attack", gate.name)
                            .withStyle(ChatFormatting.RED));
        }

        if (gate.health <= 0 && !gate.bashedOpen) {
            gate.slider.setOpen(world);

            BlockPos min = gate.portcullis.min();
            BlockPos max = gate.portcullis.max();
            RandomSource rand = world.getRandom();

            for (int i = 0; i < 10; i++) {
                double x = min.getX() + rand.nextInt(max.getX() - min.getX() + 1);
                double y = min.getY() + rand.nextInt(max.getY() - min.getY() + 1);
                double z = min.getZ() + rand.nextInt(max.getZ() - min.getZ() + 1);

                world.explode(null, x, y, z, 0.0f, Level.ExplosionInteraction.NONE);
            }

            var bashTeam = SiegeTeams.opposite(gate.flag.team);

            this.game.gameSpace.getPlayers().sendMessage(
                    Component.literal("The ")
                            .append(Component.literal(gate.name).withStyle(ChatFormatting.YELLOW))
                            .append(CommonComponents.SPACE)
                            .append(gate.pastToBe())
                            .append(" been bashed open by the ")
                            .append(bashTeam.config().name())
                            .append("!")
                            .withStyle(ChatFormatting.BOLD)
            );

            if (bashTeam == SiegeTeams.ATTACKERS) {
                var msg = "game.siege.dialogue.gate_bashed";
                Scheduler.INSTANCE
                        .submit(
                                (Consumer<MinecraftServer>) (s) -> SiegeDialogueLogic.leadersToTeams(this.game, msg),
                                40
                        );
            }

            gate.bashedOpen = true;
        } else if (gate.health >= gate.repairedHealthThreshold && gate.bashedOpen) {
            GameTeam team = gate.flag.team;
            this.game.gameSpace.getPlayers().sendMessage(
                    Component.literal("The ")
                            .append(Component.literal(gate.flag.name).withStyle(ChatFormatting.YELLOW))
                            .append(CommonComponents.SPACE)
                            .append(gate.flag.pastToBe())
                            .append(" been repaired by the ")
                            .append(team.config().name())
                            .append("!")
                            .withStyle(ChatFormatting.BOLD)
            );

            BlockPos max = gate.portcullis.max();
            world.playSound(null, max.getX(), max.getY(), max.getZ(), SoundEvents.ANVIL_USE, SoundSource.BLOCKS, 1.0f, world.getRandom().nextFloat() * 0.25F + 0.6F);

            gate.slider.setClosed(world);
            gate.bashedOpen = false;
        }

        var ownerTeamPresent = new MutablePlayerSet(world.getServer());
        var enemyTeamPresent = new MutablePlayerSet(world.getServer());

        for (Object2ObjectMap.Entry<PlayerRef, SiegePlayer> entry : Object2ObjectMaps.fastIterable(this.game.participants)) {
            ServerPlayer player = entry.getKey().getEntity(world);
            if (player == null || player.gameMode.getGameModeForPlayer() != GameType.SURVIVAL) {
                continue;
            }

            if (gate.gateOpen.contains(player.blockPosition())) {
                SiegePlayer participant = entry.getValue();
                if (participant.team == gate.flag.team) {
                    ownerTeamPresent.add(player);

                    if (participant.kit == SiegeKit.ENGINEER) {
                        ownerTeamPresent.add(player);
                    }

                } else {
                    enemyTeamPresent.add(player);
                }
            }
        }

        for (var player : ownerTeamPresent) {
            var participant = this.game.participant(player);
            if (participant == null) {
                continue;
            }

            if (gate.underAttack(time) || gate.health != gate.maxHealth) {
                if (time - participant.timeOfLastBrace > 5 * 20) {
                    var kit = participant.kit == SiegeKit.ENGINEER ? "engineer" : "general";
                    if (gate.bashedOpen) {
                        var key = String.format("game.siege.gate.repair_hint.%s", kit);
                        player.sendSystemMessage(
                                Component.translatable(key, gate.blocksToRepair()).withStyle(ChatFormatting.GOLD),
                                true
                        );
                    } else {
                        var key = String.format("game.siege.gate.brace_hint.%s", kit);
                        player.sendSystemMessage(Component.translatable(key, gate.health, gate.maxHealth)
                                .withStyle(ChatFormatting.GOLD), true);
                    }
                }
            } else if (!enemyTeamPresent.isEmpty() && !ownerTeamPresent.isEmpty()) {
                player.sendSystemMessage(Component.translatable("game.siege.gate.contested").withStyle(ChatFormatting.RED), true);
            }
        }

        if (gate.bashedOpen) {
            enemyTeamPresent.sendActionBar(Component.translatable("game.siege.gate.capture_hint")
                    .withStyle(ChatFormatting.GOLD));
            return;
        } else if (!gate.underAttack(time)) {
            enemyTeamPresent.sendActionBar(Component.translatable("game.siege.gate.bash_hint").withStyle(ChatFormatting.GOLD));
        }

        boolean shouldOpen = !ownerTeamPresent.isEmpty() && enemyTeamPresent.isEmpty();

        boolean moved = shouldOpen ? gate.tickOpen(world) : gate.tickClose(world);
        if (!moved) {
            return;
        }

        BlockPos pos = gate.portcullis.max();
        double x = pos.getX();
        double y = pos.getY();
        double z = pos.getZ();

        world.playSound(null, x, y, z, SoundEvents.LADDER_STEP, SoundSource.BLOCKS, 1.0f, world.getRandom().nextFloat() * 0.25F + 0.6F);
    }
}
