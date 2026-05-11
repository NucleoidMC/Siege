package io.github.restioson.siege.game.active.capturing;

import io.github.restioson.siege.game.map.SiegeFlag;
import org.jetbrains.annotations.NotNull;
import xyz.nucleoid.plasmid.api.game.common.team.GameTeam;

import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.world.BossEvent;

class PrerequisitesRequired implements CapturingState {
    private final List<SiegeFlag> prerequisites;

    PrerequisitesRequired(List<SiegeFlag> prerequisites) {
        this.prerequisites = prerequisites;
    }

    @Override
    public Component getTitle() {
        var text = Component.translatable("game.siege.flag.prerequisite_required");
        text.append(" ");

        for (int i = 0; i < this.prerequisites.size(); i++) {
            text.append(this.prerequisites.get(i).name);

            if (this.prerequisites.size() > 1) {
                if (i != this.prerequisites.size() - 1) {
                    text.append(this.prerequisites.size() > 2 ? ", " : " ");
                }

                if (i == this.prerequisites.size() - 2) {
                    text.append(Component.translatable("game.siege.flag.prerequisite_required.and"));
                    text.append(" ");
                }
            }
        }

        return text;
    }

    @Override
    public boolean isUnderAttack() {
        return false;
    }

    @Override
    public @NotNull BossEvent.BossBarColor getCaptureBarColorForTeam(GameTeam team) {
        return BossEvent.BossBarColor.RED;
    }

    @Override
    public @NotNull CapturingStateSidebarBlink getBlink() {
        return CapturingStateSidebarBlink.NO_BLINK;
    }
}
