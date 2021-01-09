package io.github.restioson.siege.game;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multimap;
import io.github.restioson.siege.game.active.SiegeActive;
import io.github.restioson.siege.game.map.SiegeMap;
import io.github.restioson.siege.game.map.SiegeMapLoader;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.world.GameMode;
import xyz.nucleoid.fantasy.BubbleWorldConfig;
import xyz.nucleoid.plasmid.game.*;
import xyz.nucleoid.plasmid.game.event.PlayerAddListener;
import xyz.nucleoid.plasmid.game.event.PlayerDeathListener;
import xyz.nucleoid.plasmid.game.event.RequestStartListener;
import xyz.nucleoid.plasmid.game.player.GameTeam;
import xyz.nucleoid.plasmid.map.MapTickets;

import java.util.List;

public class SiegeWaiting {
    private final GameSpace gameSpace;
    private final SiegeMap map;
    private final SiegeConfig config;

    private final TeamSelectionLobby teamSelection;

    private SiegeWaiting(GameSpace gameSpace, SiegeMap map, SiegeConfig config, TeamSelectionLobby teamSelection) {
        this.gameSpace = gameSpace;
        this.map = map;
        this.config = config;
        this.teamSelection = teamSelection;

        gameSpace.addResource(MapTickets.acquire(gameSpace.getWorld(), map.bounds));
    }

    public static GameOpenProcedure open(GameOpenContext<SiegeConfig> context) {
        SiegeMapLoader generator = new SiegeMapLoader(context.getConfig().mapConfig);
        SiegeMap map = generator.create();

        BubbleWorldConfig worldConfig = new BubbleWorldConfig()
                .setGenerator(map.asGenerator(context.getServer()))
                .setDefaultGameMode(GameMode.SPECTATOR);

        return context.createOpenProcedure(worldConfig, game -> {
            GameWaitingLobby.applyTo(game, context.getConfig().playerConfig);

            List<GameTeam> teams = ImmutableList.of(SiegeTeams.ATTACKERS, SiegeTeams.DEFENDERS);
            TeamSelectionLobby teamSelection = TeamSelectionLobby.applyTo(game, teams);

            SiegeWaiting waiting = new SiegeWaiting(game.getSpace(), map, context.getConfig(), teamSelection);

            game.on(RequestStartListener.EVENT, waiting::requestStart);
            game.on(PlayerAddListener.EVENT, waiting::addPlayer);
            game.on(PlayerDeathListener.EVENT, waiting::onPlayerDeath);
        });
    }

    private StartResult requestStart() {
        Multimap<GameTeam, ServerPlayerEntity> players = HashMultimap.create();
        this.teamSelection.allocate(players::put);

        SiegeActive.open(this.gameSpace, this.map, this.config, players);

        return StartResult.OK;
    }

    private void addPlayer(ServerPlayerEntity player) {
        this.spawnPlayer(player);
    }

    private ActionResult onPlayerDeath(ServerPlayerEntity player, DamageSource source) {
        player.setHealth(20.0F);
        this.spawnPlayer(player);
        return ActionResult.FAIL;
    }

    private void spawnPlayer(ServerPlayerEntity player) {
        SiegeSpawnLogic.resetPlayer(player, GameMode.ADVENTURE);
        SiegeSpawnLogic.spawnPlayer(player, this.map.waitingSpawn, this.gameSpace.getWorld());
    }
}
