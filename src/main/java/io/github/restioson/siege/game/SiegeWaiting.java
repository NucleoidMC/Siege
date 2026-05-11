package io.github.restioson.siege.game;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import eu.pb4.sgui.api.elements.GuiElementBuilder;
import eu.pb4.sgui.api.gui.SimpleGui;
import io.github.restioson.siege.Siege;
import io.github.restioson.siege.game.active.SiegeActive;
import io.github.restioson.siege.game.active.WarpSelectionUi;
import io.github.restioson.siege.game.map.SiegeMap;
import io.github.restioson.siege.game.map.SiegeMapLoader;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.clock.ClockState;
import net.minecraft.world.clock.PackedClockStates;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import xyz.nucleoid.fantasy.RuntimeLevelConfig;
import xyz.nucleoid.plasmid.api.game.GameOpenContext;
import xyz.nucleoid.plasmid.api.game.GameOpenProcedure;
import xyz.nucleoid.plasmid.api.game.GameResult;
import xyz.nucleoid.plasmid.api.game.GameSpace;
import xyz.nucleoid.plasmid.api.game.common.GameWaitingLobby;
import xyz.nucleoid.plasmid.api.game.common.team.GameTeamKey;
import xyz.nucleoid.plasmid.api.game.common.team.TeamSelectionLobby;
import xyz.nucleoid.plasmid.api.game.common.ui.WaitingLobbyUiLayout;
import xyz.nucleoid.plasmid.api.game.event.GameActivityEvents;
import xyz.nucleoid.plasmid.api.game.event.GamePlayerEvents;
import xyz.nucleoid.plasmid.api.game.event.GameWaitingLobbyEvents;
import xyz.nucleoid.plasmid.api.game.player.JoinAcceptor;
import xyz.nucleoid.plasmid.api.game.player.JoinAcceptorResult;
import xyz.nucleoid.plasmid.api.game.player.JoinIntent;
import xyz.nucleoid.plasmid.api.game.player.JoinOffer;
import xyz.nucleoid.plasmid.api.util.PlayerRef;
import xyz.nucleoid.plasmid.api.util.PlayerUtil;
import xyz.nucleoid.stimuli.event.EventResult;
import xyz.nucleoid.stimuli.event.item.ItemUseEvent;
import xyz.nucleoid.stimuli.event.player.PlayerDeathEvent;

import java.util.Map;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.level.GameType;

public class SiegeWaiting {
    private final ServerLevel world;
    private final GameSpace gameSpace;
    private final SiegeMap map;
    private final SiegeConfig config;

    private final Map<PlayerRef, SiegeKit> kitSelections;
    private final TeamSelectionLobby teamSelection;

    private SiegeWaiting(ServerLevel world, GameSpace gameSpace, SiegeMap map, SiegeConfig config, TeamSelectionLobby teamSelection) {
        this.world = world;
        this.gameSpace = gameSpace;
        this.map = map;
        this.config = config;
        this.teamSelection = teamSelection;
        this.kitSelections = new Object2ObjectOpenHashMap<>();
    }

    public static GameOpenProcedure open(GameOpenContext<SiegeConfig> context) {
        var config = context.config();
        SiegeMap map = SiegeMapLoader.load(context.server(), config.map());

        RuntimeLevelConfig worldConfig = new RuntimeLevelConfig()
                .setGenerator(map.asGenerator(context.server()))
                .setDimensionType(ResourceKey.create(Registries.DIMENSION_TYPE, Identifier.fromNamespaceAndPath(Siege.ID, "default")))
                .setClockManagerConstructor(new PackedClockStates(
                        Map.of(context.server().registryAccess().getOrThrow(WorldClocks.OVERWORLD), new ClockState(map.time, 0, 0, true)
                )));

        return context.openWithLevel(worldConfig, (activity, world) -> {
            GameWaitingLobby.addTo(activity, config.players());

            TeamSelectionLobby teamSelection = TeamSelectionLobby.addTo(activity, SiegeTeams.TEAMS);

            SiegeWaiting waiting = new SiegeWaiting(world, activity.getGameSpace(), map, config, teamSelection);

            activity.listen(GameActivityEvents.REQUEST_START, waiting::requestStart);
            activity.listen(GamePlayerEvents.OFFER, JoinOffer::accept);
            activity.listen(GamePlayerEvents.ACCEPT, waiting::acceptPlayer);
            activity.listen(PlayerDeathEvent.EVENT, waiting::onPlayerDeath);
            activity.listen(GamePlayerEvents.ADD, waiting::onAddPlayer);
            activity.listen(GameWaitingLobbyEvents.BUILD_UI_LAYOUT, waiting::buildLayout);
        });
    }

    private void buildLayout(WaitingLobbyUiLayout layout, ServerPlayer player) {
        if (this.gameSpace.getPlayers().spectators().contains(player)) {
            return;
        }
        var ref = PlayerRef.of(player);
        layout.addLeading(() -> GuiElementBuilder.from(SiegeKit.kitSelectItemStack())
                .setCallback(() -> {
                    SimpleGui ui = WarpSelectionUi.createKitSelect(player, this.kitSelections.get(ref), selectedKit -> {
                        this.kitSelections.put(ref, selectedKit);
                        var msg = Component.translatable("game.siege.kit.selected")
                                .append(" ")
                                .append(selectedKit.getName())
                                .withStyle(ChatFormatting.GREEN);
                        player.sendSystemMessage((msg), true);
                        PlayerUtil.playSoundToPlayer(player, SoundEvents.ARMOR_EQUIP_GENERIC.value(), SoundSource.NEUTRAL, 1.0F, 1.0F);
                    });

                    ui.open();
                })
                .build());
    }

    private void onAddPlayer(ServerPlayer player) {
    }

    private GameResult requestStart() {
        Multimap<GameTeamKey, ServerPlayer> players = HashMultimap.create();
        this.teamSelection.allocate(this.gameSpace.getPlayers(), players::put);

        SiegeActive.open(this.world, this.gameSpace, this.map, this.config, players, this.kitSelections);

        return GameResult.ok();
    }

    private JoinAcceptorResult acceptPlayer(JoinAcceptor offer) {
        return SiegeSpawnLogic.acceptPlayer(offer, this.world, this.map.waitingSpawn, GameType.ADVENTURE);
    }

    private EventResult onPlayerDeath(ServerPlayer player, DamageSource source) {
        player.setHealth(20.0F);
        SiegeSpawnLogic.resetPlayer(player, GameType.ADVENTURE);
        SiegeSpawnLogic.spawnPlayer(player, this.map.waitingSpawn, this.world);
        return EventResult.DENY;
    }
}
