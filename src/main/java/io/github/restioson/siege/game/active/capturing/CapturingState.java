package io.github.restioson.siege.game.active.capturing;

import io.github.restioson.siege.game.map.SiegeFlag;
import org.jetbrains.annotations.NotNull;
import xyz.nucleoid.plasmid.api.game.common.team.GameTeam;

import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.BossEvent;

public interface CapturingState {
    static CapturingState capturing() {
        return SimpleCapturingState.CAPTURING;
    }

    static CapturingState contested() {
        return SimpleCapturingState.CONTESTED;
    }

    static CapturingState securing() {
        return SimpleCapturingState.SECURING;
    }

    static CapturingState recaptureDisabled() {
        return SimpleCapturingState.RECAPTURE_DISABLED;
    }

    static CapturingState prerequisitesRequired(List<SiegeFlag> prerequisites) {
        return new PrerequisitesRequired(prerequisites);
    }

    Component getTitle();

    boolean isUnderAttack();

    @NotNull
    BossEvent.BossBarColor getCaptureBarColorForTeam(GameTeam team);

    @NotNull
    CapturingStateSidebarBlink getBlink();
}
