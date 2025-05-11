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
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
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

    public EventResult maybeBraceGate(BlockPos pos, SiegePlayer participant, ServerPlayerEntity player,
                                      ItemUsageContext ctx, long time) {
        for (SiegeGate gate : this.game.map.gates) {
            if (gate.brace != null && gate.brace.contains(pos)) {
                if (gate.health < gate.maxHealth) {
                    ServerWorld world = this.game.world;
                    gate.health += 1;
                    gate.broadcastHealth(player, this.game, world);
                    world.setBlockState(pos, Blocks.AIR.getDefaultState());
                    world.playSound(
                            player,
                            pos.getX(),
                            pos.getY(),
                            pos.getZ(),
                            SoundEvents.ENTITY_IRON_GOLEM_REPAIR,
                            SoundCategory.BLOCKS,
                            1.0F,
                            1.0F + gate.repairFraction()
                    );
                    ctx.getStack().decrement(1);
                    participant.timeOfLastBrace = time;
                    return EventResult.DENY;
                } else {
                    player.sendMessage(Text.literal("The gate is already at max health!").formatted(Formatting.DARK_GREEN), true);
                }
                return EventResult.DENY;
            }
        }

        return EventResult.PASS;
    }

    public static boolean canUseToBash(Item item) {
        return item.getRegistryEntry().isIn(ItemTags.SWORDS) || item.getRegistryEntry().isIn(ItemTags.SHOVELS);
    }

    public EventResult maybeBash(BlockPos pos, ServerPlayerEntity player, SiegePlayer participant, long time) {
        var mainHandItem = player.getMainHandStack();
        boolean rightKit = participant.kit == SiegeKit.SHIELD_BEARER || participant.kit == SiegeKit.SOLDIER;

        for (SiegeGate gate : this.game.map.gates) {
            if (!gate.bashedOpen && gate.health > 0 && gate.portcullis.contains(pos)) {
                var cooldownMgr = player.getItemCooldownManager();

                if (participant.team == gate.flag.team) {
                    player.sendMessage(Text.literal("You cannot bash your own gate!").formatted(Formatting.RED), true);
                    return EventResult.DENY;
                } else if (!rightKit) {
                    player.sendMessage(Text.literal("Only soldiers and shieldbearers can bash!").formatted(Formatting.RED), true);
                    return EventResult.DENY;
                } else if (!canUseToBash(mainHandItem.getItem())) {
                    player.sendMessage(Text.literal("You can only bash with a sword or axe!").formatted(Formatting.RED), true);
                    return EventResult.DENY;
                } else if (!player.isSprinting()) {
                    player.sendMessage(Text.literal("You must be sprinting to bash!").formatted(Formatting.RED), true);
                    return EventResult.DENY;
                } else if (cooldownMgr.isCoolingDown(mainHandItem)) {
                    return EventResult.DENY;
                }

                var inventory = player.getInventory();
                for (var stack : inventory.getMainStacks()) {
                    if (canUseToBash(stack.getItem())) {
                        cooldownMgr.set(stack, SharedConstants.TICKS_PER_SECOND);
                    }
                }
                if (canUseToBash(player.getOffHandStack().getItem())) {
                    cooldownMgr.set(player.getOffHandStack(), SharedConstants.TICKS_PER_SECOND);
                }

                ServerWorld world = this.game.world;
                world.createExplosion(null, pos.getX(), pos.getY(), pos.getZ(), 0.0f, World.ExplosionSourceType.NONE);
                gate.health -= 1;
                gate.timeOfLastBash = time;
                gate.broadcastHealth(player, this.game, world);

                return EventResult.DENY;
            }
        }

        return EventResult.PASS;
    }

    public void tickGate(SiegeGate gate) {
        ServerWorld world = this.game.world;

        long time = world.getTime();

        if (gate.underAttack(time)) {
            this.game.team(gate.flag.team)
                    .sendActionBar(Text.translatable("game.siege.gate.under_attack", gate.name)
                            .formatted(Formatting.RED));
        }

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

            var bashTeam = SiegeTeams.opposite(gate.flag.team);

            this.game.gameSpace.getPlayers().sendMessage(
                    Text.literal("The ")
                            .append(Text.literal(gate.name).formatted(Formatting.YELLOW))
                            .append(ScreenTexts.SPACE)
                            .append(gate.pastToBe())
                            .append(" been bashed open by the ")
                            .append(bashTeam.config().name())
                            .append("!")
                            .formatted(Formatting.BOLD)
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

        var ownerTeamPresent = new MutablePlayerSet(world.getServer());
        var enemyTeamPresent = new MutablePlayerSet(world.getServer());

        for (Object2ObjectMap.Entry<PlayerRef, SiegePlayer> entry : Object2ObjectMaps.fastIterable(this.game.participants)) {
            ServerPlayerEntity player = entry.getKey().getEntity(world);
            if (player == null || player.interactionManager.getGameMode() != GameMode.SURVIVAL) {
                continue;
            }

            if (gate.gateOpen.contains(player.getBlockPos())) {
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
                        player.sendMessage(
                                Text.translatable(key, gate.blocksToRepair()).formatted(Formatting.GOLD),
                                true
                        );
                    } else {
                        var key = String.format("game.siege.gate.brace_hint.%s", kit);
                        player.sendMessage(Text.translatable(key, gate.health, gate.maxHealth)
                                .formatted(Formatting.GOLD), true);
                    }
                }
            } else if (!enemyTeamPresent.isEmpty() && !ownerTeamPresent.isEmpty()) {
                player.sendMessage(Text.translatable("game.siege.gate.contested").formatted(Formatting.RED), true);
            }
        }

        if (gate.bashedOpen) {
            enemyTeamPresent.sendActionBar(Text.translatable("game.siege.gate.capture_hint")
                    .formatted(Formatting.GOLD));
            return;
        } else if (!gate.underAttack(time)) {
            enemyTeamPresent.sendActionBar(Text.translatable("game.siege.gate.bash_hint").formatted(Formatting.GOLD));
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

        world.playSound(null, x, y, z, SoundEvents.BLOCK_LADDER_STEP, SoundCategory.BLOCKS, 1.0f, world.random.nextFloat() * 0.25F + 0.6F);
    }
}
