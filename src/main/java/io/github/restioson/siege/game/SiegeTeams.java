package io.github.restioson.siege.game;

import org.jetbrains.annotations.Nullable;
import xyz.nucleoid.plasmid.api.game.GameActivity;
import xyz.nucleoid.plasmid.api.game.common.team.*;

import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.scores.Team;

public final class SiegeTeams {
    public static final GameTeam ATTACKERS = new GameTeam(
            new GameTeamKey("attackers"),
            GameTeamConfig.builder()
                    .setName(Component.literal("Attackers"))
                    .setColors(GameTeamConfig.Colors.from(DyeColor.RED))
                    .setCollision(Team.CollisionRule.NEVER)
                    .setFriendlyFire(false)
                    .build()
    );
    public static final GameTeam DEFENDERS = new GameTeam(
            new GameTeamKey("defenders"),
            GameTeamConfig.builder()
                    .setName(Component.literal("Defenders"))
                    .setColors(GameTeamConfig.Colors.from(DyeColor.BLUE))
                    .setCollision(Team.CollisionRule.NEVER)
                    .setFriendlyFire(false)
                    .build()
    );

    public static final GameTeamList TEAMS = new GameTeamList(List.of(ATTACKERS, DEFENDERS));

    private final TeamManager teams;

    public SiegeTeams(GameActivity activity) {
        this.teams = TeamManager.addTo(activity);
        TeamChat.addTo(activity, this.teams);
        this.teams.addTeams(TEAMS);
    }

    public static GameTeam opposite(GameTeam team) {
        return team == ATTACKERS ? DEFENDERS : ATTACKERS;
    }

    public static GameTeam byKey(GameTeamKey team) {
        return team == ATTACKERS.key() ? ATTACKERS : DEFENDERS;
    }

    @Nullable
    public static GameTeam byKey(String name) {
        return switch (name) {
            case "attackers" -> ATTACKERS;
            case "defenders" -> DEFENDERS;
            default -> null;
        };
    }

    public void addPlayer(ServerPlayer player, GameTeamKey team) {
        this.teams.addPlayerTo(player, team);
    }

    public void removePlayer(ServerPlayer player, GameTeamKey team) {
        this.teams.removePlayerFrom(player, team);
    }

    public GameTeamKey getSmallestTeam() {
        return this.teams.getSmallestTeam();
    }

    public static Component nameOf(GameTeam team) {
        return Component.translatable("game.siege.team." + team.key().id());
    }
}
