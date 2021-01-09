package io.github.restioson.siege.game.active;

import com.google.common.collect.Multimap;
import io.github.restioson.siege.entity.SiegeKitStandEntity;
import io.github.restioson.siege.game.SiegeConfig;
import io.github.restioson.siege.game.SiegeKit;
import io.github.restioson.siege.game.SiegeSpawnLogic;
import io.github.restioson.siege.game.SiegeTeams;
import io.github.restioson.siege.game.map.SiegeFlag;
import io.github.restioson.siege.game.map.SiegeGate;
import io.github.restioson.siege.game.map.SiegeKitStandLocation;
import io.github.restioson.siege.game.map.SiegeMap;
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
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.LiteralText;
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
import org.jetbrains.annotations.Nullable;
import xyz.nucleoid.plasmid.game.GameCloseReason;
import xyz.nucleoid.plasmid.game.GameSpace;
import xyz.nucleoid.plasmid.game.event.*;
import xyz.nucleoid.plasmid.game.player.GameTeam;
import xyz.nucleoid.plasmid.game.player.JoinResult;
import xyz.nucleoid.plasmid.game.player.PlayerSet;
import xyz.nucleoid.plasmid.game.rule.GameRule;
import xyz.nucleoid.plasmid.game.rule.RuleResult;
import xyz.nucleoid.plasmid.util.BlockBounds;
import xyz.nucleoid.plasmid.util.PlayerRef;
import xyz.nucleoid.plasmid.widget.GlobalWidgets;

import java.util.*;
import java.util.function.ToDoubleFunction;

public class SiegeActive {
    public final SiegeConfig config;

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

    private SiegeActive(GameSpace gameSpace, SiegeMap map, SiegeConfig config, GlobalWidgets widgets, Multimap<GameTeam, ServerPlayerEntity> players) {
        this.gameSpace = gameSpace;
        this.config = config;
        this.map = map;
        this.participants = new Object2ObjectOpenHashMap<>();
        this.warpingPlayers = new LinkedList<>();

        this.teams = gameSpace.addResource(new SiegeTeams(gameSpace));

        for (GameTeam team : players.keySet()) {
            for (ServerPlayerEntity player : players.get(team)) {
                this.participants.put(PlayerRef.of(player), new SiegePlayer(team));
                this.teams.addPlayer(player, team);
            }
        }

        this.stageManager = new SiegeStageManager(this);

        this.sidebar = new SiegeSidebar(this, widgets);

        this.captureLogic = new SiegeCaptureLogic(this);
        this.gateLogic = new SiegeGateLogic(this);
    }

    public static void open(GameSpace gameSpace, SiegeMap map, SiegeConfig config, Multimap<GameTeam, ServerPlayerEntity> players) {
        gameSpace.openGame(game -> {
            GlobalWidgets widgets = new GlobalWidgets(game);

            SiegeActive active = new SiegeActive(gameSpace, map, config, widgets, players);

            game.setRule(GameRule.CRAFTING, RuleResult.DENY);
            game.setRule(GameRule.PORTALS, RuleResult.DENY);
            game.setRule(GameRule.PVP, RuleResult.ALLOW);
            game.setRule(GameRule.HUNGER, RuleResult.ALLOW);
            game.setRule(GameRule.INTERACTION, RuleResult.ALLOW);
            game.setRule(GameRule.FALL_DAMAGE, RuleResult.ALLOW);
            game.setRule(GameRule.PLACE_BLOCKS, RuleResult.ALLOW);
            game.setRule(GameRule.UNSTABLE_TNT, RuleResult.ALLOW);

            game.on(GameOpenListener.EVENT, active::onOpen);
            game.on(GameCloseListener.EVENT, active::onClose);
            game.on(DropItemListener.EVENT, active::onDropItem);
            game.on(BreakBlockListener.EVENT, active::onBreakBlock);
            game.on(PlaceBlockListener.EVENT, active::onPlaceBlock);

            game.on(OfferPlayerListener.EVENT, player -> JoinResult.ok());
            game.on(PlayerAddListener.EVENT, active::addPlayer);
            game.on(PlayerRemoveListener.EVENT, active::removePlayer);
            game.on(UseBlockListener.EVENT, active::onUseBlock);
            game.on(ExplosionListener.EVENT, active::onExplosion);
            game.on(PlayerPunchBlockListener.EVENT, active::onHitBlock);
            game.on(UseItemListener.EVENT, active::onUseItem);

            game.on(GameTickListener.EVENT, active::tick);

            game.on(PlayerDamageListener.EVENT, active::onPlayerDamage);
            game.on(PlayerDeathListener.EVENT, active::onPlayerDeath);
            game.on(PlayerFireArrowListener.EVENT, active::onPlayerFireArrow);
            game.on(AttackEntityListener.EVENT, active::onAttackEntity);
            game.on(EntityHitListener.EVENT, active::onHitEntity);

            ServerWorld world = gameSpace.getWorld();

            for (SiegeKitStandLocation stand : active.map.kitStands) {
                SiegeKitStandEntity standEntity = new SiegeKitStandEntity(world, active, stand);
                world.spawnEntity(standEntity);

                if (standEntity.controllingFlag != null) {
                    standEntity.controllingFlag.kitStands.add(standEntity);
                }
            }
        });
    }

    private void onExplosion(List<BlockPos> affectedBlocks) {
        gate:
        for (SiegeGate gate : this.map.gates) {
            for (BlockPos pos : affectedBlocks) {
                if (!gate.bashedOpen && gate.health > 0 && gate.portcullis.contains(pos)) {
                    gate.health = Math.max(0, gate.health - TNT_GATE_DAMAGE);
                    break gate;
                }
            }
        }

        affectedBlocks.removeIf(this.map::isProtectedBlock);
    }

    private ActionResult onHitEntity(ProjectileEntity projectileEntity, EntityHitResult hitResult) {
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
            return this.gateLogic.maybeBash(pos, player, participant, this.gameSpace.getWorld().getTime());
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
        ServerWorld world = this.gameSpace.getWorld();

        String help = "Siege - capture all the flags to win! There are two teams - attackers and defenders. Defenders " +
                "must defend blue flags and attackers must capture them. To capture a flag, stand near it. To defend " +
                "it, kill the capturers, and stand near it to halt the capture progress. You can bash gates by" +
                "sprinting and hitting them as a soldier or shieldbearer. They can be braced and repaired by placing" +
                "wood near them as a constructor.";

        for (Map.Entry<PlayerRef, SiegePlayer> entry : this.participants.entrySet()) {
            entry.getKey().ifOnline(world, p -> {
                Text text = new LiteralText(help).formatted(Formatting.GOLD);
                p.sendMessage(text, false);

                if (this.config.recapture) {
                    p.sendMessage(new LiteralText("Defenders may also capture attacker's flags.").formatted(Formatting.GOLD), false);
                }

                if (this.config.defenderEnderPearl && entry.getValue().team == SiegeTeams.DEFENDERS) {
                    p.sendMessage(
                            new LiteralText("You can use your ender pearl to warp to a flag that is under attack.")
                                    .formatted(Formatting.GOLD),
                            false
                    );
                }

                this.spawnParticipant(p, this.map.getFirstSpawn(entry.getValue().team));
            });
        }

        this.stageManager.onOpen(world.getTime(), this.config);
    }

    private void onClose() {
        for (SiegeFlag flag : this.map.flags) {
            flag.closeCaptureBar();
        }
    }

    private void addPlayer(ServerPlayerEntity player) {
        if (!this.participants.containsKey(PlayerRef.of(player))) {
            this.allocateParticipant(player);
        }
        this.spawnParticipant(player, null);
    }

    private void allocateParticipant(ServerPlayerEntity player) {
        GameTeam smallestTeam = this.getSmallestTeam();
        SiegePlayer participant = new SiegePlayer(smallestTeam);
        this.participants.put(PlayerRef.of(player), participant);
        this.teams.addPlayer(player, smallestTeam);
    }

    private GameTeam getSmallestTeam() {
        // TODO: store a map of teams to players, this is bad
        int attackersCount = 0;
        int defendersCount = 0;
        for (SiegePlayer participant : this.participants.values()) {
            if (participant.team == SiegeTeams.DEFENDERS) defendersCount++;
            if (participant.team == SiegeTeams.ATTACKERS) attackersCount++;
        }

        if (attackersCount <= defendersCount) {
            return SiegeTeams.ATTACKERS;
        } else {
            return SiegeTeams.DEFENDERS;
        }
    }

    private void removePlayer(ServerPlayerEntity player) {
        SiegePlayer participant = this.participants.remove(PlayerRef.of(player));
        if (participant != null) {
            this.teams.removePlayer(player, participant.team);
        }
    }

    private ActionResult onDropItem(PlayerEntity player, int slot, ItemStack stack) {
        return ActionResult.FAIL;
    }

    private ActionResult onPlaceBlock(
            ServerPlayerEntity player,
            BlockPos blockPos,
            BlockState blockState,
            ItemUsageContext ctx
    ) {
        SiegePlayer participant = this.participant(player);
        if (participant == null) {
            return ActionResult.FAIL;
        }

        Block block = blockState.getBlock();

        int slot;
        if (ctx.getHand() == Hand.MAIN_HAND) {
            slot = player.inventory.selectedSlot;
        } else {
            slot = 40; // offhand
        }

        if (participant.kit == SiegeKit.CONSTRUCTOR && block != Blocks.TNT) {
            // TNT may be placed anyway
            for (BlockBounds noBuildRegion : this.map.noBuildRegions) {
                if (noBuildRegion.contains(blockPos)) {
                    // TODO do this in plasmid
                    player.networkHandler.sendPacket(new ScreenHandlerSlotUpdateS2CPacket(-2, slot, ctx.getStack()));
                    return ActionResult.FAIL;
                }
            }

            return this.gateLogic.maybeBraceGate(blockPos, player, slot, ctx);
        } else if (participant.kit == SiegeKit.DEMOLITIONER && block == Blocks.TNT) {
            return ActionResult.PASS;
        } else {
            player.networkHandler.sendPacket(new ScreenHandlerSlotUpdateS2CPacket(-2, slot, ctx.getStack()));
            return ActionResult.FAIL;
        }
    }

    private ActionResult onBreakBlock(ServerPlayerEntity player, BlockPos pos) {
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
        ServerWorld world = this.gameSpace.getWorld();
        Item inHand = player.getStackInHand(hand).getItem();
        if (participant != null) {
            pos.offset(hitResult.getSide());
            BlockState state = world.getBlockState(pos);
            if (state.getBlock() instanceof EnderChestBlock) {
                String error = participant.kit.restock(player, participant, world, this.config);

                if (error == null) {
                    player.sendMessage(new LiteralText("Items restocked!").formatted(Formatting.DARK_GREEN, Formatting.BOLD), true);
                } else {
                    player.sendMessage(new LiteralText(error).formatted(Formatting.RED, Formatting.BOLD), true);
                }
                return ActionResult.FAIL;
            } else if (state.getBlock() instanceof DoorBlock) {
                return ActionResult.PASS;
            } else if (state.getBlock() instanceof BlockWithEntity) {
                return ActionResult.FAIL;
            } else if (inHand == Items.STONE_AXE || inHand == Items.IRON_SWORD) {
                if (this.gateLogic.maybeBash(pos, player, participant, world.getTime()) == ActionResult.FAIL) {
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
                SiegeSpawn spawn = this.getSpawnFor(player, this.gameSpace.getWorld().getTime());
                if (spawn.flag != null && spawn.frontLine) {
                    this.warpingPlayers.add(new WarpingPlayer(player, spawn.flag, this.gameSpace.getWorld().getTime()));
                    cooldownManager.set(Items.ENDER_PEARL, 10 * 20);
                    player.sendMessage(new LiteralText(String.format("Warping to %s... hold still!", spawn.flag.name)).formatted(Formatting.GREEN), true);
                    player.playSound(SoundEvents.ENTITY_ENDER_PEARL_THROW, SoundCategory.NEUTRAL, 1.0F, 1.0F);
                } else {
                    player.sendMessage(new LiteralText("There are no flags in need of assistance").formatted(Formatting.RED), true);
                }

                int slot;
                if (hand == Hand.MAIN_HAND) {
                    slot = player.inventory.selectedSlot;
                } else {
                    slot = 40; // offhand
                }

                // TODO do this in plasmid
                player.networkHandler.sendPacket(new ScreenHandlerSlotUpdateS2CPacket(-2, slot, stack));
                return new TypedActionResult<>(ActionResult.FAIL, stack);
            }
        }

        return new TypedActionResult<>(ActionResult.PASS, stack);
    }

    private ActionResult onPlayerDamage(ServerPlayerEntity player, DamageSource source, float v) {
        SiegePlayer participant = this.participant(player);
        long time = this.gameSpace.getWorld().getTime();

        if (participant != null && this.gameSpace.getWorld().getTime() < participant.timeOfSpawn + 5 * 20 && !participant.attackedThisLife) {
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
        ServerWorld world = this.gameSpace.getWorld();
        long time = world.getTime();

        MutableText eliminationMessage = new LiteralText(" was killed by ");
        SiegePlayer attacker = null;

        if (source.getAttacker() != null) {
            eliminationMessage.append(source.getAttacker().getDisplayName());

            if (source.getAttacker() instanceof ServerPlayerEntity) {
                attacker = this.participant((ServerPlayerEntity) source.getAttacker());
            }
        } else if (participant != null && participant.attacker(time, world) != null) {
            eliminationMessage.append(participant.attacker(time, world).getDisplayName());
            attacker = this.participant(participant.attacker(time, world));
        } else if (source == DamageSource.DROWN) {
            eliminationMessage.append("forgetting to just keep swimming");
        } else {
            eliminationMessage = new LiteralText(" died");
        }

        if (attacker != null) {
            attacker.kills += 1;
        }

        if (participant != null) {
            participant.deaths += 1;
        }

        return new LiteralText("").append(player.getDisplayName()).append(eliminationMessage);
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
        player.setGameMode(GameMode.SPECTATOR);
        SiegePlayer participant = this.participant(player);

        if (participant != null) {
            participant.timeOfDeath = this.gameSpace.getWorld().getTime();
            int wood = player.inventory.count(Items.ARROW) + player.inventory.count(SiegeTeams.planksForTeam(SiegeTeams.ATTACKERS))
                    + player.inventory.count(SiegeTeams.planksForTeam(SiegeTeams.DEFENDERS));
            participant.incrementResource(SiegePersonalResource.WOOD, wood);
        }

        player.inventory.clear();
    }

    private void spawnParticipant(ServerPlayerEntity player, @Nullable BlockBounds spawnRegion) {
        player.inventory.clear();
        player.getEnderChestInventory().clear();
        SiegePlayer participant = this.participant(player);
        assert participant != null; // spawnParticipant should only be spawned on a participant
        participant.timeOfSpawn = this.gameSpace.getWorld().getTime();

        if (spawnRegion == null) {
            spawnRegion = this.getSpawnFor(player, this.gameSpace.getWorld().getTime()).bounds;
        }

        SiegeSpawnLogic.resetPlayer(player, GameMode.SURVIVAL);
        participant.kit.equipPlayer(player, participant, this.gameSpace.getWorld(), this.config);
        SiegeSpawnLogic.spawnPlayer(player, spawnRegion, this.gameSpace.getWorld());
    }

    private SiegeSpawn getSpawnFor(ServerPlayerEntity player, long time) {
        GameTeam team = this.getTeamFor(player);
        if (team == null) {
            return new SiegeSpawn(null, this.map.waitingSpawn);
        }

        SiegeSpawn respawn = new SiegeSpawn(null, this.map.waitingSpawn);
        double minDistance = Double.MAX_VALUE;

        for (SiegeFlag flag : this.map.flags) {
            if (flag.respawn != null && flag.team == team) {
                double distance = player.squaredDistanceTo(flag.respawn.getCenter());
                boolean frontLine = flag.isFrontLine(time);

                if ((distance < minDistance && frontLine == respawn.frontLine) || (frontLine && !respawn.frontLine)) {
                    respawn.setFlag(flag, frontLine);
                    minDistance = distance;
                }
            }
        }

        return respawn;
    }

    private void tick() {
        ServerWorld world = this.gameSpace.getWorld();
        long time = world.getTime();

        SiegeStageManager.TickResult result = this.stageManager.tick(time);
        if (result != SiegeStageManager.TickResult.CONTINUE_TICK) {
            switch (result) {
                case ATTACKERS_WIN:
                    this.broadcastWin(SiegeTeams.ATTACKERS);
                    break;
                case DEFENDERS_WIN:
                    this.broadcastWin(SiegeTeams.DEFENDERS);
                    break;
                case GAME_CLOSED:
                    this.gameSpace.close(GameCloseReason.FINISHED);
                    break;
            }
            return;
        }

        if (time % 20 == 0) {
            this.captureLogic.tick(world, 20);
            this.gateLogic.tick();

            this.sidebar.update(time);
            this.tickWarpingPlayers();

            if (time % (20 * 2) == 0) {
                this.tickResources(time);
            }
        }

        this.tickDead(world, time);
    }

    @Nullable
    private GameTeam getTeamFor(ServerPlayerEntity player) {
        SiegePlayer participant = this.participant(player);
        return participant != null ? participant.team : null;
    }

    private void tickWarpingPlayers() {
        ServerWorld world = this.gameSpace.getWorld();

        this.warpingPlayers.removeIf(warpingPlayer -> {
            ServerPlayerEntity player = warpingPlayer.player.getEntity(world);

            if (player == null) {
                return true;
            }

            if (player.getBlockPos() != warpingPlayer.pos) {
                player.sendMessage(new LiteralText("Warp cancelled because you moved!").formatted(Formatting.RED), true);
                player.playSound(SoundEvents.ENTITY_VILLAGER_NO, SoundCategory.NEUTRAL, 1.0F, 1.0F);
                return true;
            }

            if (world.getTime() - warpingPlayer.startTime > 20 * 3) {
                assert warpingPlayer.destination.respawn != null; // TODO remove restriction
                Vec3d pos = SiegeSpawnLogic.choosePos(player.getRandom(), warpingPlayer.destination.respawn, 0.5f);
                player.teleport(world, pos.x, pos.y, pos.z, 0.0F, 0.0F);
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
                        Text text = new LiteralText(String.format("Respawning in %ds", sec)).formatted(Formatting.BOLD);
                        p.sendMessage(text, true);
                    }

                    if (time - state.timeOfDeath > 5 * 20) {
                        this.spawnParticipant(p, null);
                    }
                }
            });
        }
    }

    static class SiegeSpawn {
        @Nullable
        SiegeFlag flag;
        boolean frontLine;
        BlockBounds bounds;

        public SiegeSpawn(@Nullable SiegeFlag flag, BlockBounds bounds) {
            this.flag = flag;

            if (flag != null) {
                this.frontLine = flag.capturingState == CapturingState.CAPTURING || flag.capturingState == CapturingState.CONTESTED;
            }

            this.bounds = bounds;
        }

        public void setFlag(SiegeFlag flag, boolean frontLine) {
            this.flag = flag;
            this.frontLine = frontLine;
            this.bounds = flag.respawn;
        }
    }

    private Optional<BestPlayer> getPlayerWithHighest(ToDoubleFunction<SiegePlayer> getter) {
        return this.participants.entrySet()
                .stream()
                .max(Comparator.comparingDouble(e -> getter.applyAsDouble(e.getValue())))
                .map(e -> {
                    ServerPlayerEntity p = e.getKey().getEntity(this.gameSpace.getWorld());

                    if (p == null) {
                        return null;
                    }

                    return new BestPlayer(p.server.getUserName(), getter.applyAsDouble(e.getValue()));
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

        Text message = new LiteralText("The ")
                .append(winningTeam.getDisplay())
                .append(" have won the game!")
                .formatted(winningTeam.getFormatting(), Formatting.BOLD);

        PlayerSet players = this.gameSpace.getPlayers();
        players.sendMessage(message);
        players.sendSound(SoundEvents.ENTITY_VILLAGER_YES);

        Optional<BestPlayer> mostKills = this.getPlayerWithHighest(p -> p.kills);
        Optional<BestPlayer> highestKd = this.getPlayerWithHighest(p -> (double) p.kills / Math.max(1, p.deaths));
        Optional<BestPlayer> mostCaptures = this.getPlayerWithHighest(p -> p.captures);
        Optional<BestPlayer> mostSecures = this.getPlayerWithHighest(p -> p.secures);

        Formatting colour = Formatting.GOLD;

        mostKills.ifPresent(p -> {
            players.sendMessage(new LiteralText(String.format("Most kills - %s with %d", p.name, (int) p.score)).formatted(colour));
        });
        highestKd.ifPresent(p -> {
            players.sendMessage(new LiteralText(String.format("Highest KD - %s with %.2f", p.name, p.score)).formatted(colour));
        });
        mostCaptures.ifPresent(p -> {
            players.sendMessage(new LiteralText(String.format("Most captures - %s with %d", p.name, (int) p.score)).formatted(colour));
        });
        mostSecures.ifPresent(p -> {
            players.sendMessage(new LiteralText(String.format("Most secures - %s with %d", p.name, (int) p.score)).formatted(colour));
        });

        int attacker_kills = 0;
        int defender_kills = 0;
        int attacker_deaths = 0;
        int defender_deaths = 0; // separate because other deaths exist

        for (Map.Entry<PlayerRef, SiegePlayer> entry : this.participants.entrySet()) {
            ServerPlayerEntity p = entry.getKey().getEntity(this.gameSpace.getWorld());

            if (p != null) {
                int kills = entry.getValue().kills;
                int deaths = entry.getValue().deaths;

                double kd = (double) kills / Math.max(1, deaths);
                MutableText text = new LiteralText("\nYour statistics:\n")
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
        players.sendMessage(new LiteralText(String.format("Attacker kills - %d", attacker_kills)).formatted(colour).formatted(bold));
        players.sendMessage(new LiteralText(String.format("Attacker deaths - %d", attacker_deaths)).formatted(colour).formatted(bold));
        players.sendMessage(new LiteralText(String.format("Attacker K/D - %.2f", attacker_kd)).formatted(colour).formatted(bold));
        players.sendMessage(new LiteralText(String.format("Defender kills - %d", attacker_kills)).formatted(colour).formatted(bold));
        players.sendMessage(new LiteralText(String.format("Defender deaths - %d", attacker_kills)).formatted(colour).formatted(bold));
        players.sendMessage(new LiteralText(String.format("Defender K/D - %.2f", defender_kd)).formatted(colour).formatted(bold));
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
