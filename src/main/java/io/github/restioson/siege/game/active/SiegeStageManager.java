package io.github.restioson.siege.game.active;

import io.github.restioson.siege.game.SiegeTeams;
import io.github.restioson.siege.game.map.SiegeFlag;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.GameType;
import xyz.nucleoid.plasmid.api.game.common.team.GameTeam;

import java.util.UUID;

public class SiegeStageManager {
    private final SiegeActive game;
    private final boolean singlePlayer;

    public final ServerBossEvent timerBar = new ServerBossEvent(
            UUID.randomUUID(),
            Component.translatable("game.siege.timer.time_left"),
            BossEvent.BossBarColor.BLUE,
            BossEvent.BossBarOverlay.PROGRESS
    );
    private long closeTime = -1;
    public long startTime = -1;
    private long maxPotentialTime = -1;
    private long finishTime = -1;

    SiegeStageManager(SiegeActive game) {
        this.game = game;
        this.singlePlayer = game.gameSpace.getPlayers().size() <= 1;
    }

    public void onOpen(long time) {
        this.startTime = time;
        this.maxPotentialTime = this.game.config.timeLimitMins() * 60L * 20L;
        this.finishTime = this.startTime + this.maxPotentialTime;
    }

    public TickResult tick(long time) {
        if (this.game.gameSpace.getPlayers().isEmpty()) {
            return TickResult.GAME_CLOSED;
        }

        // Game has finished. Wait a few seconds before finally closing the game.
        if (this.closeTime > 0) {
            return this.tickClosing(time);
        }

        if (this.testOvertime(time)) {
            this.timerBar.setName(Component.translatable("game.siege.timer.overtime").withStyle(ChatFormatting.RED));
            this.timerBar.setProgress(1.0f);
            return TickResult.OVERTIME;
        }

        if (this.testDefendersWin(time)) {
            this.triggerFinish(time);
            return TickResult.DEFENDERS_WIN;
        }

        if (this.testAttackersWin()) {
            this.triggerFinish(time);
            return TickResult.ATTACKERS_WIN;
        }

        if (!this.singlePlayer && this.game.gameSpace.getPlayers().size() <= 1) {
            GameTeam team = this.getRemainingTeam();
            this.triggerFinish(time);
            if (team == SiegeTeams.DEFENDERS) {
                return TickResult.DEFENDERS_WIN;
            } else {
                return TickResult.ATTACKERS_WIN;
            }
        }

        var timeToFinish = this.finishTime - time;
        if (timeToFinish == 20 * 60) {
            SiegeDialogueLogic.broadcastTimeRunningOut(this.game);
        }

        long ticksTillEnd = this.finishTime - time;
        long secondsUntilEnd = ticksTillEnd / 20;
        long minutes = secondsUntilEnd / 60;
        long seconds = secondsUntilEnd % 60;
        var timerBarText = Component.translatable("game.siege.timer.time_left")
                .append(" ")
                .append(
                        Component.literal(String.format("%02d:%02d", minutes, seconds))
                                .withStyle(ChatFormatting.AQUA)
                );

        this.timerBar.setName(timerBarText);
        this.timerBar.setProgress((float) ticksTillEnd / this.maxPotentialTime);

        return TickResult.CONTINUE_TICK;
    }

    private boolean testOvertime(long time) {
        return this.game.config.capturingGiveTimeSecs() > 0
                && time >= this.finishTime
                && this.game.map.flags.stream()
                        .anyMatch(flag -> flag.captureProgressTicks > 0 || flag.isFlagUnderAttack());
    }

    private TickResult tickClosing(long time) {
        if (time >= this.closeTime) {
            return TickResult.GAME_CLOSED;
        }
        return TickResult.TICK_FINISHED;
    }

    private void triggerFinish(long time) {
        this.closeTimerBar();

        for (ServerPlayer player : this.game.gameSpace.getPlayers()) {
            player.setGameMode(GameType.SPECTATOR);
        }

        this.closeTime = time + (15 * 20);
    }

    private boolean testDefendersWin(long time) {
        return time >= this.finishTime;
    }

    private boolean testAttackersWin() {
        for (SiegeFlag flag : this.game.map.flags) {
            if (flag.team != SiegeTeams.ATTACKERS) {
                return false;
            }
        }
        return true;
    }

    private GameTeam getRemainingTeam() {
        for (ServerPlayer player : this.game.gameSpace.getPlayers()) {
            SiegePlayer participant = this.game.participant(player);
            if (participant != null) {
                return participant.team;
            }
        }
        return SiegeTeams.DEFENDERS;
    }

    public void addTime(int secs) {
        long timeNow = this.game.world.getGameTime();
        if (timeNow > this.finishTime) {
            this.finishTime = timeNow;
        }

        this.maxPotentialTime += secs * 20L;
        this.finishTime += secs * 20L;
    }

    public void closeTimerBar() {
        this.timerBar.setVisible(false);
        this.timerBar.removeAllPlayers();
    }

    public enum TickResult {
        CONTINUE_TICK,
        TICK_FINISHED,
        ATTACKERS_WIN,
        DEFENDERS_WIN,
        OVERTIME,
        GAME_CLOSED;

        public boolean continueGame() {
            return this == CONTINUE_TICK || this == OVERTIME;
        }
    }
}
