package io.github.restioson.siege.game.active;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import eu.pb4.sgui.api.gui.SimpleGui;
import io.github.restioson.siege.game.SiegeConfig;
import io.github.restioson.siege.game.SiegeKit;
import io.github.restioson.siege.game.SiegeSpawnLogic;
import io.github.restioson.siege.game.SiegeTeams;
import io.github.restioson.siege.game.map.*;
import io.github.restioson.siege.item.SiegeHorn;
import io.github.restioson.siege.item.SiegeItems;
import io.github.restioson.siege.mixin.ExplosionImplAccessor;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EnderChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import xyz.nucleoid.map_templates.BlockBounds;
import xyz.nucleoid.plasmid.api.game.GameActivity;
import xyz.nucleoid.plasmid.api.game.GameCloseReason;
import xyz.nucleoid.plasmid.api.game.GameSpace;
import xyz.nucleoid.plasmid.api.game.common.GlobalWidgets;
import xyz.nucleoid.plasmid.api.game.common.PlayerLimiter;
import xyz.nucleoid.plasmid.api.game.common.team.GameTeam;
import xyz.nucleoid.plasmid.api.game.common.team.GameTeamKey;
import xyz.nucleoid.plasmid.api.game.event.GameActivityEvents;
import xyz.nucleoid.plasmid.api.game.event.GamePlayerEvents;
import xyz.nucleoid.plasmid.api.game.player.*;
import xyz.nucleoid.plasmid.api.game.rule.GameRuleType;
import xyz.nucleoid.plasmid.api.util.PlayerRef;
import xyz.nucleoid.plasmid.api.util.PlayerUtil;
import xyz.nucleoid.stimuli.event.DroppedItemsResult;
import xyz.nucleoid.stimuli.event.EventResult;
import xyz.nucleoid.stimuli.event.block.*;
import xyz.nucleoid.stimuli.event.entity.EntityDropItemsEvent;
import xyz.nucleoid.stimuli.event.item.ItemThrowEvent;
import xyz.nucleoid.stimuli.event.item.ItemUseEvent;
import xyz.nucleoid.stimuli.event.player.PlayerAttackEntityEvent;
import xyz.nucleoid.stimuli.event.player.PlayerDamageEvent;
import xyz.nucleoid.stimuli.event.player.PlayerDeathEvent;
import xyz.nucleoid.stimuli.event.projectile.ArrowFireEvent;
import xyz.nucleoid.stimuli.event.projectile.ProjectileHitEvent;
import xyz.nucleoid.stimuli.event.world.ExplosionDetonatedEvent;

import java.util.*;
import java.util.function.ToDoubleFunction;

public class SiegeActive {
    private static final int RESPAWN_DELAY_TICKS = 5 * 20;
    private static final int WARP_DELAY_TICKS = 2 * 20;
    private final static List<Item> PLANKS = List.of(
            SiegeKit.KitResource.PLANKS.attackerItem(),
            SiegeKit.KitResource.PLANKS.defenderItem()
    );
    public static int TNT_GATE_DAMAGE = 10;
    public final SiegeConfig config;
    public final ServerLevel world;
    public final GameSpace gameSpace;
    public final Object2ObjectMap<PlayerRef, SiegePlayer> participants;
    public final Map<PlayerRef, WarpingPlayer> warpingPlayers;
    final SiegeTeams teams;
    final SiegeStageManager stageManager;
    final SiegeSidebar sidebar;
    final SiegeCaptureLogic captureLogic;
    final SiegeGateLogic gateLogic;
    public SiegeMap map;

    private SiegeActive(ServerLevel world, GameActivity activity, SiegeMap map, SiegeConfig config,
                        GlobalWidgets widgets, Multimap<GameTeamKey, ServerPlayer> players,
                        Map<PlayerRef, SiegeKit> kitSelections) {
        this.world = world;
        this.gameSpace = activity.getGameSpace();
        this.config = config;
        this.map = map;
        this.participants = new Object2ObjectOpenHashMap<>();
        this.warpingPlayers = new Object2ObjectOpenHashMap<>();

        this.teams = new SiegeTeams(activity);

        for (GameTeamKey key : players.keySet()) {
            for (ServerPlayer player : players.get(key)) {
                var ref = PlayerRef.of(player);
                this.participants.put(ref, new SiegePlayer(SiegeTeams.byKey(key), kitSelections.get(ref)));
                this.teams.addPlayer(player, key);
            }
        }

        this.stageManager = new SiegeStageManager(this);
        this.sidebar = new SiegeSidebar(this, widgets);

        this.captureLogic = new SiegeCaptureLogic(this);
        this.gateLogic = new SiegeGateLogic(this);
    }

    public static void open(ServerLevel world, GameSpace gameSpace, SiegeMap map, SiegeConfig config,
                            Multimap<GameTeamKey, ServerPlayer> players,
                            Map<PlayerRef, SiegeKit> kitSelections) {
        gameSpace.setActivity(activity -> {
            GlobalWidgets widgets = GlobalWidgets.addTo(activity);

            SiegeActive active = new SiegeActive(world, activity, map, config, widgets, players, kitSelections);

            activity.deny(GameRuleType.CRAFTING);
            activity.deny(GameRuleType.PORTALS);
            activity.allow(GameRuleType.PVP);
            activity.allow(GameRuleType.HUNGER);
            activity.allow(GameRuleType.INTERACTION);
            activity.allow(GameRuleType.FALL_DAMAGE);
            activity.allow(GameRuleType.PLACE_BLOCKS);
            activity.allow(GameRuleType.UNSTABLE_TNT);
            activity.listen(BlockDropItemsEvent.EVENT, active::onBlockDrop);

            activity.listen(GameActivityEvents.ENABLE, active::onOpen);
            activity.listen(GameActivityEvents.DISABLE, active::onClose);
            activity.listen(ItemThrowEvent.EVENT, active::onDropItem);
            activity.listen(EntityDropItemsEvent.EVENT, active::onEntityDropItem);
            activity.listen(BlockBreakEvent.EVENT, active::onBreakBlock);
            activity.listen(BlockPlaceEvent.BEFORE, active::onPlaceBlock);

            PlayerLimiter.addTo(activity, config.players().playerConfig());

            //activity.listen(GamePlayerEvents.OFFER, active::acceptPlayer);
            activity.listen(GamePlayerEvents.ACCEPT, active::acceptPlayer);
            activity.listen(GamePlayerEvents.REMOVE, active::removePlayer);
            activity.listen(BlockUseEvent.EVENT, active::onUseBlock);
            activity.listen(ExplosionDetonatedEvent.EVENT, active::onExplosion);
            activity.listen(BlockPunchEvent.EVENT, active::onHitBlock);
            activity.listen(ItemUseEvent.EVENT, active::onUseItem);

            activity.listen(GameActivityEvents.TICK, active::tick);

            activity.listen(PlayerDamageEvent.EVENT, active::onPlayerDamage);
            activity.listen(PlayerDeathEvent.EVENT, active::onPlayerDeath);
            activity.listen(ArrowFireEvent.EVENT, active::onPlayerFireArrow);
            activity.listen(PlayerAttackEntityEvent.EVENT, active::onAttackEntity);
            activity.listen(ProjectileHitEvent.ENTITY, active::onProjectileHitEntity);

            active.map.startGame(active);
        });
    }

    private static void sendDescription(ServerPlayer player, String base, int n) {
        var msg = Component.empty();
        for (int i = 1; i <= n; i++) {
            msg.append(Component.translatable(String.format("%s.desc.%s", base, i)).append(" "));
        }
        player.sendSystemMessage(msg.withStyle(ChatFormatting.GOLD), false);
    }

    private DroppedItemsResult onEntityDropItem(LivingEntity livingEntity, List<ItemStack> itemStacks) {
        return DroppedItemsResult.deny();
    }

    private DroppedItemsResult onBlockDrop(Entity entity, ServerLevel world, BlockPos blockPos, BlockState blockState, List<ItemStack> itemStacks) {
        itemStacks.removeIf(stack -> !PLANKS.contains(stack.getItem()));
        return DroppedItemsResult.allow(itemStacks);
    }

    private EventResult onExplosion(Explosion explosion, List<BlockPos> blockPos) {
        if (!(explosion.getIndirectSourceEntity() instanceof ServerPlayer player)) {
            return EventResult.PASS;
        }

        var participant = this.participant(player);

        if (participant == null) {
            return EventResult.PASS;
        }

        gate:
        for (SiegeGate gate : this.map.gates) {
            if (participant.team == gate.flag.team) {
                continue;
            }

            for (BlockPos pos : blockPos) {
                if (!gate.bashedOpen && gate.health > 0 && gate.portcullis.contains(pos)) {
                    gate.health = Math.max(0, gate.health - TNT_GATE_DAMAGE);
                    gate.timeOfLastBash = this.world.getGameTime();
                    gate.broadcastHealth(player, this, this.world);
                    break gate;
                }
            }
        }

        var dmg = ((ExplosionImplAccessor) explosion).getDamageCalculator().getEntityDamageAmount(explosion, player, explosion.radius());
        if (dmg > 0) {
            player.hurtServer(this.world, Explosion.getDefaultDamageSource(this.world, null), dmg);
        }

        blockPos.removeIf(this.map::isProtectedBlock);

        return EventResult.PASS;
    }

    private EventResult onProjectileHitEntity(Projectile projectileEntity, EntityHitResult hitResult) {
        if (hitResult.getEntity() instanceof ServerPlayer) {
            return EventResult.PASS;
        } else {
            return EventResult.DENY;
        }
    }

    private EventResult onAttackEntity(ServerPlayer playerEntity, InteractionHand hand, Entity entity, EntityHitResult entityHitResult) {
        if (entity instanceof ServerPlayer) {
            return EventResult.PASS;
        } else {
            return EventResult.DENY;
        }
    }

    private EventResult onHitBlock(ServerPlayer player, Direction direction, BlockPos pos) {
        SiegePlayer participant = this.participant(player);
        if (participant != null) {
            return this.gateLogic.maybeBash(pos, player, participant, this.world.getGameTime());
        } else {
            return EventResult.PASS;
        }
    }

    @Nullable
    public SiegePlayer participant(ServerPlayer player) {
        return this.participant(PlayerRef.of(player));
    }

    @Nullable
    public SiegePlayer participant(PlayerRef player) {
        return this.participants.get(player);
    }

    public PlayerSet team(GameTeam team) {
        var teamPlayers = new MutablePlayerSet(this.world.getServer());
        for (var entry : this.participants.entrySet()) {
            if (entry.getValue().team == team) {
                teamPlayers.add(entry.getKey());
            }
        }
        return teamPlayers;
    }

    public void showTitle(GameTeam team, Component title, @Nullable Component subtitle) {
        var stay = 5 * 20;
        var fadeIn = 5;
        var fadeOut = 10;

        if (subtitle != null) {
            this.team(team).showTitle(title, subtitle, fadeIn, stay, fadeOut);
        } else {
            this.team(team).showTitle(title, fadeIn, stay, fadeOut);
        }
    }

    private void onOpen() {
        for (Map.Entry<PlayerRef, SiegePlayer> entry : this.participants.entrySet()) {
            entry.getKey().ifOnline(this.world, p -> {
                var participant = entry.getValue();
                if (this.config.recapture()) {
                    sendDescription(p, "game.siege.recapture", 3);
                }

                if (this.config.hasEnderPearl(participant.team)) {
                    sendDescription(p, "game.siege.enderpearl", 2);
                }

                if (this.config.capturingGiveTimeSecs() > 0) {
                    sendDescription(p, "game.siege.quick", 2);
                }

                this.spawnParticipant(p, this.map.getFirstSpawn(participant.team));
            });
        }

        this.showTitle(
                SiegeTeams.ATTACKERS,
                Component.translatable("game.siege.start.attacker.title")
                        .withStyle(ChatFormatting.RED),
                Component.translatable("game.siege.start.attacker.subtitle")
        );

        this.showTitle(
                SiegeTeams.DEFENDERS,
                Component.translatable("game.siege.start.defender.title")
                        .withStyle(ChatFormatting.AQUA),
                Component.translatable("game.siege.start.defender.subtitle")
        );

        if (SiegeMapLoader.loadRemote()) {
            var hint = Component.literal("[Siege] Loaded map from build server").withStyle(ChatFormatting.AQUA);
            this.gameSpace.getPlayers().sendMessage(hint);
        }

        this.stageManager.onOpen(this.world.getGameTime());
    }

    private void onClose() {
        for (SiegeFlag flag : this.map.flags) {
            flag.closeCaptureBar();
        }

        this.stageManager.closeTimerBar();
    }

    private JoinAcceptorResult acceptPlayer(JoinAcceptor offer) {
        return SiegeSpawnLogic.acceptPlayer(offer, this.world, this.map.waitingSpawn, offer.intent() == JoinIntent.PLAY ? GameType.SURVIVAL : GameType.SPECTATOR)
                .thenRunForEach((player, intent) -> {
                    if (intent == JoinIntent.PLAY) {
                        var spawn = this.getSpawnFor(player, this.world.getGameTime());
                        var pos = SiegeSpawnLogic.choosePos(world.getRandom(), spawn.spawn.bounds(), 0.5F);
                        player.teleportTo(world, pos.x, pos.y, pos.z, Set.of(), 0, 0, false);
                        if (!this.participants.containsKey(PlayerRef.of(player))) {
                            this.allocateParticipant(player);
                        }
                        this.completeParticipantSpawn(player);
                    }
                });
    }

    private void allocateParticipant(ServerPlayer player) {
        GameTeamKey smallestTeam = this.teams.getSmallestTeam();
        SiegePlayer participant = new SiegePlayer(SiegeTeams.byKey(smallestTeam), null);
        this.participants.put(PlayerRef.of(player), participant);
        this.teams.addPlayer(player, smallestTeam);
    }

    private void removePlayer(ServerPlayer player) {
        SiegePlayer participant = this.participants.remove(PlayerRef.of(player));
        if (participant != null) {
            this.teams.removePlayer(player, participant.team.key());
        }
    }

    private EventResult onDropItem(Player player, int slot, ItemStack stack) {
        return EventResult.DENY;
    }

    private EventResult onPlaceBlock(
            ServerPlayer player,
            ServerLevel world,
            BlockPos blockPos,
            BlockState blockState,
            UseOnContext ctx
    ) {
        SiegePlayer participant = this.participant(player);
        if (participant == null) {
            return EventResult.DENY;
        }

        if (participant.kit == SiegeKit.ENGINEER) {
            // TNT may be placed anyway
            for (BlockBounds noBuildRegion : this.map.noBuildRegions) {
                if (noBuildRegion.contains(blockPos)) {
                    return EventResult.DENY;
                }
            }

            if (this.map.isProtectedBlock(blockPos.asLong())) {
                return EventResult.DENY;
            }

            return this.gateLogic.maybeBraceGate(blockPos, participant, player, ctx, this.world.getGameTime());
        } else {
            return EventResult.DENY;
        }
    }

    private EventResult onBreakBlock(ServerPlayer player, ServerLevel world, BlockPos pos) {
        if (this.map.isProtectedBlock(pos.asLong())) {
            return EventResult.DENY;
        }
        return EventResult.PASS;
    }

    private InteractionResult onUseBlock(ServerPlayer player, InteractionHand hand, BlockHitResult hitResult) {
        BlockPos pos = hitResult.getBlockPos();
        if (pos == null) {
            return InteractionResult.PASS;
        }

        SiegePlayer participant = this.participant(player);
        Item inHand = player.getItemInHand(hand).getItem();
        if (participant != null) {
            pos.relative(hitResult.getDirection());
            BlockState state = this.world.getBlockState(pos);
            if (state.getBlock() instanceof EnderChestBlock) {
                MutableComponent result = participant.kit.restock(player, participant, this.world.getGameTime()).copy();
                player.sendSystemMessage(result.withStyle(ChatFormatting.BOLD), true);
                return InteractionResult.FAIL;
            } else if (state.getBlock() instanceof DoorBlock) {
                return InteractionResult.PASS;
            } else if (state.getBlock() instanceof BaseEntityBlock) {
                return InteractionResult.FAIL;
            } else if (SiegeGateLogic.canUseToBash(inHand)) {
                if (this.gateLogic.maybeBash(pos, player, participant, this.world.getGameTime()) == EventResult.DENY) {
                    return InteractionResult.FAIL;
                }
            }

            // Disable log stripping
            if (inHand instanceof AxeItem) {
                return InteractionResult.FAIL;
            }

            return InteractionResult.PASS;
        }

        return InteractionResult.PASS;
    }

    private InteractionResult onUseItem(ServerPlayer player, InteractionHand hand) {
        SiegePlayer participant = this.participant(player);
        ItemStack stack = player.getItemInHand(hand);
        if (participant != null) {
            ItemCooldowns cooldownManager = player.getCooldowns();

            if (cooldownManager.isOnCooldown(stack)) {
                return InteractionResult.FAIL;
            }

            if (stack.is(Items.ENDER_PEARL)) {
                SimpleGui ui = WarpSelectionUi.createFlagWarp(player, this.map, participant.team, selectedFlag -> {
                    cooldownManager.addCooldown(stack, 10 * 20);
                    cooldownManager.addCooldown(SiegeKit.KIT_SELECT_ITEM.getDefaultInstance(), SiegeKit.KIT_SWAP_COOLDOWN);

                    this.warpingPlayers.put(
                            PlayerRef.of(player),
                            new WarpingPlayer(
                                    player,
                                    selectedFlag.getRespawnFor(participant.team),
                                    this.world.getGameTime(),
                                    null
                            )
                    );

                    player.sendSystemMessage(Component.literal(String.format("Warping to %s... hold still!", selectedFlag.name))
                            .withStyle(ChatFormatting.GREEN), true);
                    PlayerUtil.playSoundToPlayer(player, SoundEvents.ENDER_PEARL_THROW, SoundSource.NEUTRAL, 1.0F, 1.0F);
                });

                ui.open();

                return InteractionResult.FAIL;
            } else if (stack.is(SiegeKit.KIT_SELECT_ITEM)) {
                SimpleGui ui = WarpSelectionUi.createKitSelect(player, participant.kit, selectedKit -> {
                    long time = player.level().getGameTime();
                    var spawn = this.getSpawnFor(player, time);

                    cooldownManager.addCooldown(Items.ENDER_PEARL.getDefaultInstance(), 10 * 20);
                    cooldownManager.addCooldown(SiegeKit.KIT_SELECT_ITEM.getDefaultInstance(), 10 * 20);
                    this.warpingPlayers.put(
                            PlayerRef.of(player),
                            new WarpingPlayer(player, spawn.spawn, this.world.getGameTime(), selectedKit)
                    );

                    var msg = Component.literal("Respawning as ").append(selectedKit.getName());

                    if (spawn.flag != null) {
                        msg.append(" at ").append(spawn.flag.name);
                    }

                    player.sendSystemMessage(msg.append("... hold still!").withStyle(ChatFormatting.GREEN), true);
                    PlayerUtil.playSoundToPlayer(player, SoundEvents.ENDER_PEARL_THROW, SoundSource.NEUTRAL, 1.0F, 1.0F);
                });

                ui.open();

                return InteractionResult.FAIL;
            } else if (stack.is(SiegeItems.HORN)) {
                return SiegeHorn.onUse(this, player, participant, stack, hand);
            }
        }

        return InteractionResult.PASS;
    }

    private EventResult onPlayerDamage(ServerPlayer player, DamageSource source, float v) {
        SiegePlayer participant = this.participant(player);
        long time = this.world.getGameTime();

        if (participant != null && this.world.getGameTime() < participant.timeOfSpawn + RESPAWN_DELAY_TICKS && !participant.attackedThisLife) {
            return EventResult.DENY;
        }

        if (participant != null && source.getEntity() != null && source.getEntity() instanceof ServerPlayer) {
            PlayerRef attacker = PlayerRef.of((ServerPlayer) source.getEntity());
            participant.lastTimeWasAttacked = new AttackRecord(attacker, time);

            SiegePlayer attackerParticipant = this.participant(attacker);
            if (attackerParticipant != null) {
                attackerParticipant.attackedThisLife = true;
            }
        }

        var ref = PlayerRef.of(player);
        var warping = this.warpingPlayers.get(ref);
        if (warping != null) {
            this.warpingPlayers.remove(ref);
            player.sendSystemMessage(Component.literal("Cancelled because you took damage!").withStyle(ChatFormatting.RED), true);
            PlayerUtil.playSoundToPlayer(player, SoundEvents.VILLAGER_NO, SoundSource.NEUTRAL, 1.0F, 1.0F);
        }

        return EventResult.DENY;
    }

    private EventResult onPlayerDeath(ServerPlayer player, DamageSource source) {
        MutableComponent deathMessage = this.getDeathMessageAndIncStats(player, source);
        this.gameSpace.getPlayers().sendMessage(deathMessage.withStyle(ChatFormatting.GRAY));

        this.spawnDeadParticipant(player);

        return EventResult.DENY;
    }

    private MutableComponent getDeathMessageAndIncStats(ServerPlayer player, DamageSource source) {
        SiegePlayer participant = this.participant(player);
        var world = this.world;
        long time = world.getGameTime();

        MutableComponent eliminationMessage = Component.literal(" was ");
        SiegePlayer attacker = null;

        if (source.is(DamageTypeTags.IS_EXPLOSION)) {
            eliminationMessage.append("blown up");
        } else {
            eliminationMessage.append("killed");
        }

        eliminationMessage.append(" by ");

        if (source.getEntity() != null) {
            eliminationMessage.append(source.getEntity().getDisplayName());

            if (source.getEntity() instanceof ServerPlayer) {
                attacker = this.participant((ServerPlayer) source.getEntity());
            }
        } else if (participant != null && participant.attacker(time, world) != null) {
            eliminationMessage.append(participant.attacker(time, world).getDisplayName());
            attacker = this.participant(participant.attacker(time, world));
        } else if (source.is(DamageTypeTags.IS_DROWNING)) {
            eliminationMessage.append("forgetting to just keep swimming");
        } else {
            eliminationMessage = Component.literal(" died");
        }

        if (attacker != null) {
            attacker.kills += 1;
        }

        if (participant != null) {
            participant.deaths += 1;
        }

        return Component.empty().append(player.getDisplayName()).append(eliminationMessage);
    }

    private EventResult onPlayerFireArrow(
            ServerPlayer user,
            ItemStack tool,
            ArrowItem arrowItem,
            int remainingUseTicks,
            AbstractArrow projectile
    ) {
        projectile.pickup = AbstractArrow.Pickup.DISALLOWED;
        return EventResult.DENY;
    }

    private void spawnDeadParticipant(ServerPlayer player) {
        player.getEnderChestInventory().clearContent();
        player.setGameMode(GameType.SPECTATOR);
        SiegePlayer participant = this.participant(player);

        if (participant != null) {
            participant.timeOfDeath = this.world.getGameTime();
            participant.kit.returnResources(player, participant);
        }
    }

    private void spawnParticipant(ServerPlayer player, @Nullable SiegeSpawn spawn) {
        if (spawn == null) {
            spawn = this.getSpawnFor(player, this.world.getGameTime()).spawn;
        }

        this.stageManager.timerBar.addPlayer(player);
        SiegeSpawnLogic.resetPlayer(player, GameType.SURVIVAL);
        SiegeSpawnLogic.spawnPlayer(player, spawn, this.world);

        this.completeParticipantSpawn(player);
    }

    private void completeParticipantSpawn(ServerPlayer player) {
        player.getInventory().clearContent();
        player.getEnderChestInventory().clearContent();
        SiegePlayer participant = this.participant(player);
        assert participant != null; // spawnParticipant should only be called on a participant

        var time = this.world.getGameTime();
        participant.timeOfSpawn = time;
        participant.kit.equipPlayer(player, participant, this.config, time);
    }

    private SiegeSpawnResult getSpawnFor(ServerPlayer player, long time) {
        GameTeam team = this.getTeamFor(player);
        if (team == null) {
            return new SiegeSpawnResult(null, this.map.waitingSpawn);
        }

        SiegeSpawnResult respawn = new SiegeSpawnResult(null, this.map.waitingSpawn);
        double minDistance = Double.MAX_VALUE;

        for (SiegeFlag flag : this.map.flags) {
            SiegeSpawn flagRespawn = flag.getRespawnFor(team);
            if (flagRespawn != null && flag.team == team) {
                double distance = player.distanceToSqr(flagRespawn.bounds().center());
                boolean frontLine = flag.isFrontLine(time);

                if ((distance < minDistance && frontLine == respawn.isFrontLine(time)) ||
                        (frontLine && !respawn.isFrontLine(time))) {
                    respawn.setFlag(flag, flagRespawn);
                    minDistance = distance;
                }
            }
        }

        return respawn;
    }

    private void tick() {
        long time = this.world.getGameTime();

        SiegeStageManager.TickResult result = this.stageManager.tick(time);
        if (!result.continueGame()) {
            switch (result) {
                case ATTACKERS_WIN -> this.broadcastWin(SiegeTeams.ATTACKERS);
                case DEFENDERS_WIN -> this.broadcastWin(SiegeTeams.DEFENDERS);
                case GAME_CLOSED -> this.gameSpace.close(GameCloseReason.FINISHED);
            }

            return;
        }

        if (time % 20 == 0) {
            this.captureLogic.tick(this.world, 20);
            this.gateLogic.tick();
            this.sidebar.update(time);
            this.tickResources(time);
        }

        this.tickWarpingPlayers(time);

        this.tickDead(this.world, time);
    }

    @Nullable
    private GameTeam getTeamFor(ServerPlayer player) {
        SiegePlayer participant = this.participant(player);
        return participant != null ? participant.team : null;
    }

    private void tickWarpingPlayers(long time) {
        this.warpingPlayers.values().removeIf(warpingPlayer -> {
            ServerPlayer player = warpingPlayer.player.getEntity(this.world);
            var participant = this.participant(player);

            if (player == null || participant == null) {
                return true;
            }

            if (this.world.getGameTime() - warpingPlayer.startTime > WARP_DELAY_TICKS) {
                SiegeSpawn respawn = warpingPlayer.destination;
                assert respawn != null; // TODO remove restriction
                Vec3 pos = SiegeSpawnLogic.choosePos(player.getRandom(), respawn.bounds(), 0.5f);
                player.teleportTo(this.world, pos.x, pos.y, pos.z, Set.of(), respawn.yaw(), 0.0F, false);
                PlayerUtil.playSoundToPlayer(player, SoundEvents.ENDERMAN_TELEPORT, SoundSource.NEUTRAL, 1.0F, 1.0F);

                if (warpingPlayer.newKit != null) {
                    warpingPlayer.newKit.equipPlayer(player, participant, this.config, time);
                }
                return true;
            }

            // Set X and Y as relative so it will send 0 change when we pass yaw (yaw - yaw = 0) and pitch
            Set<Relative> flags = ImmutableSet.of(Relative.X_ROT, Relative.Y_ROT);

            // Teleport without changing the pitch and yaw
            player.connection.teleport(new PositionMoveRotation(warpingPlayer.pos, Vec3.ZERO, 0, 0), flags);

            return false;
        });
    }

    private void tickResources(long time) {
        for (SiegePersonalResource resource : SiegePersonalResource.values()) {
            if (time % (resource.refreshSecs * 20L) == 0) {
                for (SiegePlayer player : this.participants.values()) {
                    player.incrementResource(resource, 1);
                }
            }
        }
    }

    private void tickDead(ServerLevel world, long time) {
        for (Object2ObjectMap.Entry<PlayerRef, SiegePlayer> entry : Object2ObjectMaps.fastIterable(this.participants)) {
            PlayerRef ref = entry.getKey();
            SiegePlayer state = entry.getValue();
            ref.ifOnline(world, p -> {
                if (p.isSpectator()) {
                    int sec = 5 - (int) Math.floor((time - state.timeOfDeath) / 20.0f);

                    if (sec > 0 && (time - state.timeOfDeath) % 20 == 0) {
                        Component text = Component.literal(String.format("Respawning in %ds", sec)).withStyle(ChatFormatting.BOLD);
                        p.sendSystemMessage(text, true);
                    }

                    if (time - state.timeOfDeath > RESPAWN_DELAY_TICKS) {
                        this.spawnParticipant(p, null);
                    }
                }
            });
        }
    }

    private void broadcastWin(GameTeam winningTeam) {
        for (Map.Entry<PlayerRef, SiegePlayer> entry : this.participants.entrySet()) {
            entry.getKey().ifOnline(this.gameSpace.getServer(), player -> {
                if (entry.getValue().team == winningTeam && winningTeam == SiegeTeams.DEFENDERS) {
                    PlayerUtil.playSoundToPlayer(player, SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundSource.MASTER, 1.0F, 1.0F);
                    player.addEffect(new MobEffectInstance(MobEffects.HERO_OF_THE_VILLAGE, 10, 0, false, false, true));
                }
            });
        }

        PlayerSet players = this.gameSpace.getPlayers();
        SiegeDialogueLogic.broadcastWin(this, winningTeam);

        Optional<BestPlayer> mostKills = this.getPlayerWithHighest(p -> p.kills);
        Optional<BestPlayer> highestKd = this.getPlayerWithHighest(p -> (double) p.kills / Math.max(1, p.deaths));
        Optional<BestPlayer> mostCaptures = this.getPlayerWithHighest(p -> p.captures);
        Optional<BestPlayer> mostSecures = this.getPlayerWithHighest(p -> p.secures);

        ChatFormatting colour = ChatFormatting.GOLD;

        mostKills.ifPresent(p -> players.sendMessage(Component.literal(String.format("Most kills - %s with %d", p.name, (int) p.score)).withStyle(colour)));
        highestKd.ifPresent(p -> players.sendMessage(Component.literal(String.format("Highest KD - %s with %.2f", p.name, p.score)).withStyle(colour)));
        mostCaptures.ifPresent(p -> players.sendMessage(Component.literal(String.format("Most captures - %s with %d", p.name, (int) p.score)).withStyle(colour)));
        mostSecures.ifPresent(p -> players.sendMessage(Component.literal(String.format("Most secures - %s with %d", p.name, (int) p.score)).withStyle(colour)));

        int attacker_kills = 0;
        int defender_kills = 0;
        int attacker_deaths = 0;
        int defender_deaths = 0; // separate because other deaths exist

        for (Map.Entry<PlayerRef, SiegePlayer> entry : this.participants.entrySet()) {
            ServerPlayer p = entry.getKey().getEntity(this.world);

            if (p != null) {
                int kills = entry.getValue().kills;
                int deaths = entry.getValue().deaths;

                double kd = (double) kills / Math.max(1, deaths);
                MutableComponent text = Component.literal("\nYour statistics:\n")
                        .append(String.format("Kills - %d\n", kills))
                        .append(String.format("Deaths - %d\n", deaths))
                        .append(String.format("K/D - %.2f\n", kd));

                if (entry.getValue().team == SiegeTeams.DEFENDERS) {
                    text.append(String.format("Secures - %d", entry.getValue().secures));
                } else {
                    text.append(String.format("Captures - %d", entry.getValue().captures));
                }

                p.sendSystemMessage(text.withStyle(colour), false);

                if (entry.getValue().team == SiegeTeams.DEFENDERS) {
                    defender_kills += kills;
                    defender_deaths += deaths;
                } else {
                    attacker_kills += kills;
                    attacker_deaths += deaths;
                }
            }
        }

        double attacker_kd = (double) attacker_kills / Math.max(attacker_deaths, 1);
        double defender_kd = (double) defender_kills / Math.max(defender_deaths, 1);

        ChatFormatting bold = ChatFormatting.BOLD;
        players.sendMessage(Component.literal(String.format("Attacker kills - %d", attacker_kills)).withStyle(colour).withStyle(bold));
        players.sendMessage(Component.literal(String.format("Attacker deaths - %d", attacker_deaths)).withStyle(colour).withStyle(bold));
        players.sendMessage(Component.literal(String.format("Attacker K/D - %.2f", attacker_kd)).withStyle(colour).withStyle(bold));
        players.sendMessage(Component.literal(String.format("Defender kills - %d", defender_kills)).withStyle(colour).withStyle(bold));
        players.sendMessage(Component.literal(String.format("Defender deaths - %d", defender_deaths)).withStyle(colour).withStyle(bold));
        players.sendMessage(Component.literal(String.format("Defender K/D - %.2f", defender_kd)).withStyle(colour).withStyle(bold));
    }

    private Optional<BestPlayer> getPlayerWithHighest(ToDoubleFunction<SiegePlayer> getter) {
        return this.participants.entrySet()
                .stream()
                .max(Comparator.comparingDouble(e -> getter.applyAsDouble(e.getValue())))
                .map(e -> {
                    ServerPlayer p = e.getKey().getEntity(this.world);

                    if (p == null) {
                        return null;
                    }

                    return new BestPlayer(p.nameAndId().name(), getter.applyAsDouble(e.getValue()));
                });
    }

    static class SiegeSpawnResult {
        @Nullable
        SiegeFlag flag;
        SiegeSpawn spawn;

        public SiegeSpawnResult(@Nullable SiegeFlag flag, SiegeSpawn spawn) {
            this.flag = flag;
            this.spawn = spawn;
        }

        public void setFlag(SiegeFlag flag, SiegeSpawn spawn) {
            this.flag = flag;
            this.spawn = spawn;
        }

        public boolean isFrontLine(long time) {
            return this.flag != null && this.flag.isFrontLine(time);
        }
    }

    static class BestPlayer {
        String name;
        double score;

        public BestPlayer(String name, double score) {
            this.name = name;
            this.score = score;
        }
    }
}
