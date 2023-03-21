package io.github.restioson.siege.game.active;

import com.google.common.collect.Multimap;
import eu.pb4.sgui.api.gui.SimpleGui;
import io.github.restioson.siege.entity.SiegeKitStandEntity;
import io.github.restioson.siege.game.SiegeConfig;
import io.github.restioson.siege.game.SiegeKit;
import io.github.restioson.siege.game.SiegeSpawnLogic;
import io.github.restioson.siege.game.SiegeTeams;
import io.github.restioson.siege.game.map.*;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.block.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.ItemCooldownManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.item.*;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.GameMode;
import net.minecraft.world.explosion.Explosion;
import org.jetbrains.annotations.Nullable;
import xyz.nucleoid.map_templates.BlockBounds;
import xyz.nucleoid.plasmid.game.GameActivity;
import xyz.nucleoid.plasmid.game.GameCloseReason;
import xyz.nucleoid.plasmid.game.GameSpace;
import xyz.nucleoid.plasmid.game.common.GlobalWidgets;
import xyz.nucleoid.plasmid.game.common.team.GameTeam;
import xyz.nucleoid.plasmid.game.common.team.GameTeamKey;
import xyz.nucleoid.plasmid.game.event.GameActivityEvents;
import xyz.nucleoid.plasmid.game.event.GamePlayerEvents;
import xyz.nucleoid.plasmid.game.player.PlayerOffer;
import xyz.nucleoid.plasmid.game.player.PlayerOfferResult;
import xyz.nucleoid.plasmid.game.player.PlayerSet;
import xyz.nucleoid.plasmid.game.rule.GameRuleType;
import xyz.nucleoid.plasmid.util.PlayerRef;
import xyz.nucleoid.stimuli.event.block.BlockBreakEvent;
import xyz.nucleoid.stimuli.event.block.BlockPlaceEvent;
import xyz.nucleoid.stimuli.event.block.BlockPunchEvent;
import xyz.nucleoid.stimuli.event.block.BlockUseEvent;
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
    public final SiegeConfig config;

    public final ServerWorld world;
    public final GameSpace gameSpace;
    final SiegeMap map;

    final SiegeTeams teams;

    public final Object2ObjectMap<PlayerRef, SiegePlayer> participants;
    public final List<WarpingPlayer> warpingPlayers;
    final SiegeStageManager stageManager;

    final SiegeSidebar sidebar;

    final SiegeCaptureLogic captureLogic;
    final SiegeGateLogic gateLogic;

    public static int TNT_GATE_DAMAGE = 15;

    private SiegeActive(ServerWorld world, GameActivity activity, SiegeMap map, SiegeConfig config, GlobalWidgets widgets, Multimap<GameTeamKey, ServerPlayerEntity> players) {
        this.world = world;
        this.gameSpace = activity.getGameSpace();
        this.config = config;
        this.map = map;
        this.participants = new Object2ObjectOpenHashMap<>();
        this.warpingPlayers = new LinkedList<>();

        this.teams = new SiegeTeams(activity);

        for (GameTeamKey key : players.keySet()) {
            for (ServerPlayerEntity player : players.get(key)) {
                this.participants.put(PlayerRef.of(player), new SiegePlayer(SiegeTeams.byKey(key)));
                this.teams.addPlayer(player, key);
            }
        }

        this.stageManager = new SiegeStageManager(this);

        this.sidebar = new SiegeSidebar(this, widgets);

        this.captureLogic = new SiegeCaptureLogic(this);
        this.gateLogic = new SiegeGateLogic(this);
    }

    public static void open(ServerWorld world, GameSpace gameSpace, SiegeMap map, SiegeConfig config, Multimap<GameTeamKey, ServerPlayerEntity> players) {
        gameSpace.setActivity(activity -> {
            GlobalWidgets widgets = GlobalWidgets.addTo(activity);

            SiegeActive active = new SiegeActive(world, activity, map, config, widgets, players);

            activity.deny(GameRuleType.CRAFTING);
            activity.deny(GameRuleType.PORTALS);
            activity.allow(GameRuleType.PVP);
            activity.allow(GameRuleType.HUNGER);
            activity.allow(GameRuleType.INTERACTION);
            activity.allow(GameRuleType.FALL_DAMAGE);
            activity.allow(GameRuleType.PLACE_BLOCKS);
            activity.allow(GameRuleType.UNSTABLE_TNT);

            activity.listen(GameActivityEvents.ENABLE, active::onOpen);
            activity.listen(GameActivityEvents.DISABLE, active::onClose);
            activity.listen(ItemThrowEvent.EVENT, active::onDropItem);
            activity.listen(BlockBreakEvent.EVENT, active::onBreakBlock);
            activity.listen(BlockPlaceEvent.BEFORE, active::onPlaceBlock);

            activity.listen(GamePlayerEvents.OFFER, active::offerPlayer);
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

            for (SiegeKitStandLocation stand : active.map.kitStands) {
                SiegeKitStandEntity standEntity = new SiegeKitStandEntity(world, active, stand);
                world.spawnEntity(standEntity);

                if (standEntity.controllingFlag != null) {
                    standEntity.controllingFlag.kitStands.add(standEntity);
                }
            }
        });
    }

    private void onExplosion(Explosion explosion, boolean particles) {
        gate:
        for (SiegeGate gate : this.map.gates) {
            for (BlockPos pos : explosion.getAffectedBlocks()) {
                if (!gate.bashedOpen && gate.health > 0 && gate.portcullis.contains(pos)) {
                    gate.health = Math.max(0, gate.health - TNT_GATE_DAMAGE);
                    gate.timeOfLastBash = this.world.getTime();
                    break gate;
                }
            }
        }

        explosion.getAffectedBlocks().removeIf(this.map::isProtectedBlock);
    }

    private ActionResult onProjectileHitEntity(ProjectileEntity projectileEntity, EntityHitResult hitResult) {
        if (hitResult.getEntity() instanceof ServerPlayerEntity) {
            return ActionResult.PASS;
        } else {
            return ActionResult.FAIL;
        }
    }

    private ActionResult onAttackEntity(ServerPlayerEntity playerEntity, Hand hand, Entity entity, EntityHitResult entityHitResult) {
        if (entity instanceof ServerPlayerEntity) {
            return ActionResult.PASS;
        } else {
            return ActionResult.FAIL;
        }
    }

    private ActionResult onHitBlock(ServerPlayerEntity player, Direction direction, BlockPos pos) {
        SiegePlayer participant = this.participant(player);
        if (participant != null) {
            return this.gateLogic.maybeBash(pos, player, participant, this.world.getTime());
        } else {
            return ActionResult.PASS;
        }
    }

    @Nullable
    public SiegePlayer participant(ServerPlayerEntity player) {
        return this.participant(PlayerRef.of(player));
    }

    @Nullable
    public SiegePlayer participant(PlayerRef player) {
        return this.participants.get(player);
    }

    private void onOpen() {
        String help = "Siege - capture all the flags to win! There are two teams - attackers and defenders. Defenders " +
                "must defend blue flags and attackers must capture them. To capture a flag, stand near it. To defend " +
                "it, kill the capturers, and stand near it to halt the capture progress. You can bash gates by" +
                "sprinting and hitting them as a soldier or shieldbearer. They can be braced and repaired by placing" +
                "wood near them as a constructor.";

        for (Map.Entry<PlayerRef, SiegePlayer> entry : this.participants.entrySet()) {
            entry.getKey().ifOnline(this.world, p -> {
                Text text = Text.literal(help).formatted(Formatting.GOLD);
                p.sendMessage(text, false);

                if (this.config.recapture()) {
                    p.sendMessage(Text.literal("Defenders may also capture attacker's flags.").formatted(Formatting.GOLD), false);
                }

                if (this.config.defenderEnderPearl() && entry.getValue().team == SiegeTeams.DEFENDERS) {
                    p.sendMessage(
                            Text.literal("You can use your ender pearl to warp to a flag that is under attack.")
                                    .formatted(Formatting.GOLD),
                            false
                    );
                }

                this.spawnParticipant(p, this.map.getFirstSpawn(entry.getValue().team));
            });
        }

        this.stageManager.onOpen(this.world.getTime(), this.config);
    }

    private void onClose() {
        for (SiegeFlag flag : this.map.flags) {
            flag.closeCaptureBar();
        }
    }

    private PlayerOfferResult offerPlayer(PlayerOffer offer) {
        var player = offer.player();
        if (!this.participants.containsKey(PlayerRef.of(player))) {
            this.allocateParticipant(player);
        }

        var spawn = this.getSpawnFor(player, this.world.getTime());
        return SiegeSpawnLogic.acceptPlayer(offer, this.world, spawn.spawn, GameMode.SURVIVAL)
                .and(() -> this.completeParticipantSpawn(player));
    }

    private void allocateParticipant(ServerPlayerEntity player) {
        GameTeamKey smallestTeam = this.teams.getSmallestTeam();
        SiegePlayer participant = new SiegePlayer(SiegeTeams.byKey(smallestTeam));
        this.participants.put(PlayerRef.of(player), participant);
        this.teams.addPlayer(player, smallestTeam);
    }

    private void removePlayer(ServerPlayerEntity player) {
        SiegePlayer participant = this.participants.remove(PlayerRef.of(player));
        if (participant != null) {
            this.teams.removePlayer(player, participant.team.key());
        }
    }

    private ActionResult onDropItem(PlayerEntity player, int slot, ItemStack stack) {
        return ActionResult.FAIL;
    }

    private ActionResult onPlaceBlock(
            ServerPlayerEntity player,
            ServerWorld world,
            BlockPos blockPos,
            BlockState blockState,
            ItemUsageContext ctx
    ) {
        SiegePlayer participant = this.participant(player);
        if (participant == null) {
            return ActionResult.FAIL;
        }

        Block block = blockState.getBlock();

        if (participant.kit == SiegeKit.CONSTRUCTOR && block != Blocks.TNT) {
            // TNT may be placed anyway
            for (BlockBounds noBuildRegion : this.map.noBuildRegions) {
                if (noBuildRegion.contains(blockPos)) {
                    return ActionResult.FAIL;
                }
            }

            return this.gateLogic.maybeBraceGate(blockPos, player, ctx);
        } else if (participant.kit == SiegeKit.DEMOLITIONER && block == Blocks.TNT) {
            return ActionResult.PASS;
        } else {
            return ActionResult.FAIL;
        }
    }

    private ActionResult onBreakBlock(ServerPlayerEntity player, ServerWorld world, BlockPos pos) {
        if (this.map.isProtectedBlock(pos.asLong())) {
            return ActionResult.FAIL;
        }
        return ActionResult.PASS;
    }

    private ActionResult onUseBlock(ServerPlayerEntity player, Hand hand, BlockHitResult hitResult) {
        BlockPos pos = hitResult.getBlockPos();
        if (pos == null) {
            return ActionResult.PASS;
        }

        SiegePlayer participant = this.participant(player);
        Item inHand = player.getStackInHand(hand).getItem();
        if (participant != null) {
            pos.offset(hitResult.getSide());
            BlockState state = this.world.getBlockState(pos);
            if (state.getBlock() instanceof EnderChestBlock) {
                String error = participant.kit.restock(player, participant, this.world, this.config);

                if (error == null) {
                    player.sendMessage(Text.literal("Items restocked!").formatted(Formatting.DARK_GREEN, Formatting.BOLD), true);
                } else {
                    player.sendMessage(Text.literal(error).formatted(Formatting.RED, Formatting.BOLD), true);
                }
                return ActionResult.FAIL;
            } else if (state.getBlock() instanceof DoorBlock) {
                return ActionResult.PASS;
            } else if (state.getBlock() instanceof BlockWithEntity) {
                return ActionResult.FAIL;
            } else if (inHand == Items.STONE_AXE || inHand == Items.IRON_SWORD) {
                if (this.gateLogic.maybeBash(pos, player, participant, this.world.getTime()) == ActionResult.FAIL) {
                    return ActionResult.FAIL;
                }
            }

            if (inHand == Items.WOODEN_AXE || inHand == Items.STONE_AXE) {
                return ActionResult.FAIL;
            }

            return ActionResult.PASS;
        }

        return ActionResult.PASS;
    }

    private TypedActionResult<ItemStack> onUseItem(ServerPlayerEntity player, Hand hand) {
        SiegePlayer participant = this.participant(player);
        ItemStack stack = player.getStackInHand(hand);
        Item item = stack.getItem();
        if (participant != null) {
            ItemCooldownManager cooldownManager = player.getItemCooldownManager();

            if (item == Items.ENDER_PEARL && !cooldownManager.isCoolingDown(Items.ENDER_PEARL)) {
                SimpleGui ui = WarpSelectionUi.create(player, this.map, participant.team, selectedFlag -> {
                    if (cooldownManager.isCoolingDown(Items.ENDER_PEARL)) {
                        return;
                    }
                    cooldownManager.set(Items.ENDER_PEARL, 10 * 20);

                    this.warpingPlayers.add(new WarpingPlayer(player, selectedFlag, this.world.getTime()));
                    player.sendMessage(Text.literal(String.format("Warping to %s... hold still!", selectedFlag.name)).formatted(Formatting.GREEN), true);
                    player.playSound(SoundEvents.ENTITY_ENDER_PEARL_THROW, SoundCategory.NEUTRAL, 1.0F, 1.0F);
                });

                ui.open();

                return new TypedActionResult<>(ActionResult.FAIL, stack);
            }
        }

        return new TypedActionResult<>(ActionResult.PASS, stack);
    }

    private ActionResult onPlayerDamage(ServerPlayerEntity player, DamageSource source, float v) {
        SiegePlayer participant = this.participant(player);
        long time = this.world.getTime();

        if (participant != null && this.world.getTime() < participant.timeOfSpawn + 5 * 20 && !participant.attackedThisLife) {
            return ActionResult.FAIL;
        }

        if (participant != null && source.getAttacker() != null && source.getAttacker() instanceof ServerPlayerEntity) {
            PlayerRef attacker = PlayerRef.of((ServerPlayerEntity) source.getAttacker());
            participant.lastTimeWasAttacked = new AttackRecord(attacker, time);

            SiegePlayer attackerParticipant = this.participant(attacker);
            if (attackerParticipant != null) {
                attackerParticipant.attackedThisLife = true;
            }
        }

        return ActionResult.PASS;
    }

    private ActionResult onPlayerDeath(ServerPlayerEntity player, DamageSource source) {
        MutableText deathMessage = this.getDeathMessageAndIncStats(player, source);
        this.gameSpace.getPlayers().sendMessage(deathMessage.formatted(Formatting.GRAY));

        this.spawnDeadParticipant(player);

        return ActionResult.FAIL;
    }

    private MutableText getDeathMessageAndIncStats(ServerPlayerEntity player, DamageSource source) {
        SiegePlayer participant = this.participant(player);
        var world = this.world;
        long time = world.getTime();

        MutableText eliminationMessage = Text.literal(" was killed by ");
        SiegePlayer attacker = null;

        if (source.getAttacker() != null) {
            eliminationMessage.append(source.getAttacker().getDisplayName());

            if (source.getAttacker() instanceof ServerPlayerEntity) {
                attacker = this.participant((ServerPlayerEntity) source.getAttacker());
            }
        } else if (participant != null && participant.attacker(time, world) != null) {
            eliminationMessage.append(participant.attacker(time, world).getDisplayName());
            attacker = this.participant(participant.attacker(time, world));
        } else if (source.isIn(DamageTypeTags.IS_DROWNING)) {
            eliminationMessage.append("forgetting to just keep swimming");
        } else {
            eliminationMessage = Text.literal(" died");
        }

        if (attacker != null) {
            attacker.kills += 1;
        }

        if (participant != null) {
            participant.deaths += 1;
        }

        return Text.empty().append(player.getDisplayName()).append(eliminationMessage);
    }

    private ActionResult onPlayerFireArrow(
            ServerPlayerEntity user,
            ItemStack tool,
            ArrowItem arrowItem,
            int remainingUseTicks,
            PersistentProjectileEntity projectile
    ) {
        projectile.pickupType = PersistentProjectileEntity.PickupPermission.DISALLOWED;
        return ActionResult.PASS;
    }

    private void spawnDeadParticipant(ServerPlayerEntity player) {
        player.getEnderChestInventory().clear();
        player.changeGameMode(GameMode.SPECTATOR);
        SiegePlayer participant = this.participant(player);

        var inventory = player.getInventory();
        if (participant != null) {
            participant.timeOfDeath = this.world.getTime();
            int wood = inventory.count(Items.ARROW) + inventory.count(SiegeTeams.planksForTeam(SiegeTeams.ATTACKERS.key()))
                    + inventory.count(SiegeTeams.planksForTeam(SiegeTeams.DEFENDERS.key()));
            participant.incrementResource(SiegePersonalResource.WOOD, wood);
        }

        inventory.clear();
    }

    private void spawnParticipant(ServerPlayerEntity player, @Nullable SiegeSpawn spawn) {
        if (spawn == null) {
            spawn = this.getSpawnFor(player, this.world.getTime()).spawn;
        }

        SiegeSpawnLogic.resetPlayer(player, GameMode.SURVIVAL);
        SiegeSpawnLogic.spawnPlayer(player, spawn, this.world);

        this.completeParticipantSpawn(player);
    }

    private void completeParticipantSpawn(ServerPlayerEntity player) {
        player.getInventory().clear();
        player.getEnderChestInventory().clear();
        SiegePlayer participant = this.participant(player);
        assert participant != null; // spawnParticipant should only be spawned on a participant

        participant.timeOfSpawn = this.world.getTime();
        participant.kit.equipPlayer(player, participant, this.world, this.config);
    }

    private SiegeSpawnResult getSpawnFor(ServerPlayerEntity player, long time) {
        GameTeam team = this.getTeamFor(player);
        if (team == null) {
            return new SiegeSpawnResult(null, this.map.waitingSpawn);
        }

        SiegeSpawnResult respawn = new SiegeSpawnResult(null, this.map.waitingSpawn);
        double minDistance = Double.MAX_VALUE;

        for (SiegeFlag flag : this.map.flags) {
            SiegeSpawn flagRespawn = flag.getRespawnFor(team);
            if (flagRespawn != null && flag.team == team) {
                double distance = player.squaredDistanceTo(flagRespawn.bounds().center());
                boolean frontLine = flag.isFrontLine(time);

                if ((distance < minDistance && frontLine == respawn.frontLine) || (frontLine && !respawn.frontLine)) {
                    respawn.setFlag(flag, flagRespawn, frontLine);
                    minDistance = distance;
                }
            }
        }

        return respawn;
    }

    private void tick() {
        long time = this.world.getTime();

        SiegeStageManager.TickResult result = this.stageManager.tick(time);
        if (result != SiegeStageManager.TickResult.CONTINUE_TICK) {
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
            this.tickWarpingPlayers();

            if (time % (20 * 2) == 0) {
                this.tickResources(time);
            }
        }

        this.tickDead(this.world, time);
    }

    @Nullable
    private GameTeam getTeamFor(ServerPlayerEntity player) {
        SiegePlayer participant = this.participant(player);
        return participant != null ? participant.team : null;
    }

    private void tickWarpingPlayers() {
        this.warpingPlayers.removeIf(warpingPlayer -> {
            ServerPlayerEntity player = warpingPlayer.player.getEntity(this.world);

            if (player == null) {
                return true;
            }

            if (player.getBlockPos() != warpingPlayer.pos) {
                player.sendMessage(Text.literal("Warp cancelled because you moved!").formatted(Formatting.RED), true);
                player.playSound(SoundEvents.ENTITY_VILLAGER_NO, SoundCategory.NEUTRAL, 1.0F, 1.0F);
                return true;
            }

            if (this.world.getTime() - warpingPlayer.startTime > 20 * 3) {
                SiegeSpawn respawn = warpingPlayer.destination.getRespawnFor(warpingPlayer.destination.team);
                assert respawn != null; // TODO remove restriction
                Vec3d pos = SiegeSpawnLogic.choosePos(player.getRandom(), respawn.bounds(), 0.5f);
                player.teleport(this.world, pos.x, pos.y, pos.z, respawn.yaw(), 0.0F);
                player.playSound(SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.NEUTRAL, 1.0F, 1.0F);
                return true;
            }

            return false;
        });
    }

    private void tickResources(long time) {
        for (SiegePlayer player : this.participants.values()) {
            player.incrementResource(SiegePersonalResource.WOOD, 1);

            if (time % (60 * 20) == 0) {
                player.incrementResource(SiegePersonalResource.TNT, 1);
            }
        }
    }

    private void tickDead(ServerWorld world, long time) {
        for (Object2ObjectMap.Entry<PlayerRef, SiegePlayer> entry : Object2ObjectMaps.fastIterable(this.participants)) {
            PlayerRef ref = entry.getKey();
            SiegePlayer state = entry.getValue();
            ref.ifOnline(world, p -> {
                if (p.isSpectator()) {
                    int sec = 5 - (int) Math.floor((time - state.timeOfDeath) / 20.0f);

                    if (sec > 0 && (time - state.timeOfDeath) % 20 == 0) {
                        Text text = Text.literal(String.format("Respawning in %ds", sec)).formatted(Formatting.BOLD);
                        p.sendMessage(text, true);
                    }

                    if (time - state.timeOfDeath > 5 * 20) {
                        this.spawnParticipant(p, null);
                    }
                }
            });
        }
    }

    static class SiegeSpawnResult {
        @Nullable
        SiegeFlag flag;
        boolean frontLine;
        SiegeSpawn spawn;

        public SiegeSpawnResult(@Nullable SiegeFlag flag, SiegeSpawn spawn) {
            this.flag = flag;

            if (flag != null) {
                this.frontLine = flag.capturingState == CapturingState.CAPTURING || flag.capturingState == CapturingState.CONTESTED;
            }

            this.spawn = spawn;
        }

        public void setFlag(SiegeFlag flag, SiegeSpawn spawn, boolean frontLine) {
            this.flag = flag;
            this.frontLine = frontLine;
            this.spawn = spawn;
        }
    }

    private Optional<BestPlayer> getPlayerWithHighest(ToDoubleFunction<SiegePlayer> getter) {
        return this.participants.entrySet()
                .stream()
                .max(Comparator.comparingDouble(e -> getter.applyAsDouble(e.getValue())))
                .map(e -> {
                    ServerPlayerEntity p = e.getKey().getEntity(this.world);

                    if (p == null) {
                        return null;
                    }

                    return new BestPlayer(p.getEntityName(), getter.applyAsDouble(e.getValue()));
                });
    }

    private void broadcastWin(GameTeam winningTeam) {
        for (Map.Entry<PlayerRef, SiegePlayer> entry : this.participants.entrySet()) {
            entry.getKey().ifOnline(this.gameSpace.getServer(), player -> {
                if (entry.getValue().team == winningTeam && winningTeam == SiegeTeams.DEFENDERS) {
                    player.playSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.MASTER, 1.0F, 1.0F);
                    player.addStatusEffect(new StatusEffectInstance(StatusEffects.HERO_OF_THE_VILLAGE, 10, 0, false, false, true));
                }

                if (winningTeam == SiegeTeams.ATTACKERS) {
                    player.playSound(SoundEvents.ENTITY_RAVAGER_CELEBRATE, SoundCategory.MASTER, 1.0F, 1.0F);
                }

                if (winningTeam == SiegeTeams.DEFENDERS) {
                    player.playSound(SoundEvents.ENTITY_VILLAGER_CELEBRATE, SoundCategory.MASTER, 1.0F, 1.0F);
                }
            });
        }

        Text message = Text.literal("The ")
                .append(winningTeam.config().name())
                .append(" have won the game!")
                .formatted(winningTeam.config().chatFormatting(), Formatting.BOLD);

        PlayerSet players = this.gameSpace.getPlayers();
        players.sendMessage(message);
        players.playSound(SoundEvents.ENTITY_VILLAGER_YES);

        Optional<BestPlayer> mostKills = this.getPlayerWithHighest(p -> p.kills);
        Optional<BestPlayer> highestKd = this.getPlayerWithHighest(p -> (double) p.kills / Math.max(1, p.deaths));
        Optional<BestPlayer> mostCaptures = this.getPlayerWithHighest(p -> p.captures);
        Optional<BestPlayer> mostSecures = this.getPlayerWithHighest(p -> p.secures);

        Formatting colour = Formatting.GOLD;

        mostKills.ifPresent(p -> {
            players.sendMessage(Text.literal(String.format("Most kills - %s with %d", p.name, (int) p.score)).formatted(colour));
        });
        highestKd.ifPresent(p -> {
            players.sendMessage(Text.literal(String.format("Highest KD - %s with %.2f", p.name, p.score)).formatted(colour));
        });
        mostCaptures.ifPresent(p -> {
            players.sendMessage(Text.literal(String.format("Most captures - %s with %d", p.name, (int) p.score)).formatted(colour));
        });
        mostSecures.ifPresent(p -> {
            players.sendMessage(Text.literal(String.format("Most secures - %s with %d", p.name, (int) p.score)).formatted(colour));
        });

        int attacker_kills = 0;
        int defender_kills = 0;
        int attacker_deaths = 0;
        int defender_deaths = 0; // separate because other deaths exist

        for (Map.Entry<PlayerRef, SiegePlayer> entry : this.participants.entrySet()) {
            ServerPlayerEntity p = entry.getKey().getEntity(this.world);

            if (p != null) {
                int kills = entry.getValue().kills;
                int deaths = entry.getValue().deaths;

                double kd = (double) kills / Math.max(1, deaths);
                MutableText text = Text.literal("\nYour statistics:\n")
                        .append(String.format("Kills - %d\n", kills))
                        .append(String.format("Deaths - %d\n", deaths))
                        .append(String.format("K/D - %.2f\n", kd));

                if (entry.getValue().team == SiegeTeams.DEFENDERS) {
                    text.append(String.format("Secures - %d", entry.getValue().secures));
                } else {
                    text.append(String.format("Captures - %d", entry.getValue().captures));
                }

                p.sendMessage(text.formatted(colour), false);

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

        Formatting bold = Formatting.BOLD;
        // TODO cleanup
        players.sendMessage(Text.literal(String.format("Attacker kills - %d", attacker_kills)).formatted(colour).formatted(bold));
        players.sendMessage(Text.literal(String.format("Attacker deaths - %d", attacker_deaths)).formatted(colour).formatted(bold));
        players.sendMessage(Text.literal(String.format("Attacker K/D - %.2f", attacker_kd)).formatted(colour).formatted(bold));
        players.sendMessage(Text.literal(String.format("Defender kills - %d", defender_kills)).formatted(colour).formatted(bold));
        players.sendMessage(Text.literal(String.format("Defender deaths - %d", defender_deaths)).formatted(colour).formatted(bold));
        players.sendMessage(Text.literal(String.format("Defender K/D - %.2f", defender_kd)).formatted(colour).formatted(bold));
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
