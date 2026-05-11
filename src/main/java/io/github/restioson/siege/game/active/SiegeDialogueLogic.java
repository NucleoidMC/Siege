package io.github.restioson.siege.game.active;

import io.github.restioson.siege.game.SiegeTeams;
import xyz.nucleoid.plasmid.api.game.common.team.GameTeam;
import xyz.nucleoid.plasmid.api.game.player.PlayerSet;
import xyz.nucleoid.plasmid.api.util.Scheduler;

import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

public class SiegeDialogueLogic {
    private static Component leaderName(GameTeam team) {
        return Component.translatable("game.siege.character." + team.key().id());
    }

    private static SoundEvent soundForLeader(GameTeam team) {
        return team == SiegeTeams.ATTACKERS ? SoundEvents.RAVAGER_AMBIENT : SoundEvents.VILLAGER_AMBIENT;
    }

    private static SoundEvent soundForTeam(GameTeam team) {
        return team == SiegeTeams.ATTACKERS ? SoundEvents.PILLAGER_CELEBRATE :
                SoundEvents.VILLAGER_CELEBRATE;
    }

    private static void leaderToPlayers(PlayerSet players, GameTeam team, String keyPrefix) {
        var msg = Component.empty()
                .append(
                        Component.literal("<")
                                .append(leaderName(team))
                                .append("> ")
                                .withStyle(team.config().chatFormatting())
                )
                .append(Component.translatable("%s.%s.leader".formatted(keyPrefix, team.key().id())))
                .withStyle(ChatFormatting.BOLD);
        players.sendMessage(msg);
        players.playSound(SiegeDialogueLogic.soundForLeader(team), SoundSource.NEUTRAL, 64.0f, 1.0f);
    }

    private static void chorusToPlayers(PlayerSet players, GameTeam team, String keyPrefix) {
        var msg = Component.empty()
                .append(
                        Component.literal("<")
                                .append(SiegeTeams.nameOf(team))
                                .append("> ")
                                .withStyle(team.config().chatFormatting())
                )
                .append(Component.translatable("%s.%s.chorus".formatted(keyPrefix, team.key().id())))
                .withStyle(ChatFormatting.BOLD);

        players.sendMessage(msg);
        players.playSound(SiegeDialogueLogic.soundForTeam(team), SoundSource.NEUTRAL, 64.0f, 1.0f);
    }

    public static void leadersToTeams(SiegeActive game, String keyPrefix) {
        leaderToTeam(game, SiegeTeams.ATTACKERS, keyPrefix);
        leaderToTeam(game, SiegeTeams.DEFENDERS, keyPrefix);
    }

    public static void leaderToTeam(SiegeActive game, GameTeam team, String keyPrefix) {
        leaderToPlayers(game.team(team), team, keyPrefix);
    }

    public static void leaderToAll(SiegeActive game, GameTeam team, String keyPrefix) {
        leaderToPlayers(game.gameSpace.getPlayers(), team, keyPrefix);
    }

    public static void chorusToAll(SiegeActive game, GameTeam team, String keyPrefix) {
        chorusToPlayers(game.gameSpace.getPlayers(), team, keyPrefix);
    }

    public static void broadcastWin(SiegeActive game, GameTeam winner) {
        GameTeam loser = SiegeTeams.opposite(winner);

        GameTeam[] dialogueOrder = {
                winner,
                loser,
                winner,
                loser
        };

        String base = "game.siege.dialogue.victory.%s".formatted(winner.key().id());

        AtomicInteger dialogueN = new AtomicInteger();
        Scheduler.INSTANCE.repeatWhile(
                s -> {
                    GameTeam teamSpeaking = dialogueOrder[dialogueN.get()];

                    if (dialogueN.get() <= 1) {
                        leaderToAll(game, teamSpeaking, base);  // Team leaders speak first
                    } else {
                        chorusToAll(game, teamSpeaking, base);  // ... and then the choruses
                    }
                },
                t -> dialogueN.incrementAndGet() < 4,
                30,
                60
        );
    }

    public static void broadcastTimeRunningOut(SiegeActive game) {
        leadersToTeams(game, "game.siege.dialogue.time_low");
    }
}
