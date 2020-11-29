package io.github.restioson.siege.game.active;

import io.github.restioson.siege.game.SiegeConfig;
import io.github.restioson.siege.game.SiegeTeams;
import io.github.restioson.siege.game.map.SiegeFlag;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.GameMode;

public class SiegeStageManager {
    private final SiegeActive game;

    private long closeTime = -1;
    public long finishTime = -1;

    SiegeStageManager(SiegeActive game) {
        this.game = game;
    }

    public void onOpen(long time, SiegeConfig config) {
        this.finishTime = time + (config.timeLimitMins * 20 * 60);
    }

    public TickResult tick(long time) {
        if (this.game.gameSpace.getPlayers().isEmpty()) {
            return TickResult.GAME_CLOSED;
        }

        // Game has finished. Wait a few seconds before finally closing the game.
        if (this.closeTime > 0) {
            return this.tickClosing(time);
        }

        if (this.testDefendersWin(time)) {
            this.triggerFinish(time);
            return TickResult.DEFENDERS_WIN;
        }

        if (this.testAttackersWin()) {
            this.triggerFinish(time);
            return TickResult.ATTACKERS_WIN;
        }

        return TickResult.CONTINUE_TICK;
    }

    private TickResult tickClosing(long time) {
        if (time >= this.closeTime) {
            return TickResult.GAME_CLOSED;
        }
        return TickResult.TICK_FINISHED;
    }

    private void triggerFinish(long time) {
        for (ServerPlayerEntity player : this.game.gameSpace.getPlayers()) {
            player.setGameMode(GameMode.SPECTATOR);
        }

        this.closeTime = time + (5 * 20);
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

    public enum TickResult {
        CONTINUE_TICK,
        TICK_FINISHED,
        ATTACKERS_WIN,
        DEFENDERS_WIN,
        GAME_CLOSED,
    }
}