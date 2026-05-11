package io.github.restioson.siege.game.active.capturing;

import io.github.restioson.siege.game.SiegeTeams;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.BossEvent;
import org.jetbrains.annotations.NotNull;
import xyz.nucleoid.plasmid.api.game.common.team.GameTeam;

import static io.github.restioson.siege.game.active.capturing.CapturingStateSidebarBlink.*;

enum SimpleCapturingState implements CapturingState {
    CAPTURING(Component.literal("Capturing..").withStyle(ChatFormatting.GOLD), true, OWNING_TEAM_TO_CAPTURING),
    CONTESTED(Component.literal("Contested!").withStyle(ChatFormatting.GRAY), true, OWNING_TEAM_TO_GREY),
    SECURING(Component.literal("Securing..").withStyle(ChatFormatting.AQUA), false, OWNING_TEAM_TO_CAPTURING),
    RECAPTURE_DISABLED(Component.literal("Recapture is disabled for this game!").withStyle(ChatFormatting.RED), false, NO_BLINK);

    private final Component name;
    private final boolean isUnderAttack;
    private final CapturingStateSidebarBlink blink;

    SimpleCapturingState(Component name, boolean isUnderAttack, CapturingStateSidebarBlink blink) {
        this.name = name;
        this.isUnderAttack = isUnderAttack;
        this.blink = blink;
    }

    public Component getTitle() {
        return this.name;
    }

    @Override
    public boolean isUnderAttack() {
        return this.isUnderAttack;
    }

    @Override
    public @NotNull BossEvent.BossBarColor getCaptureBarColorForTeam(GameTeam flagOwner) {
        return switch (this) {
            case CAPTURING -> flagOwner == SiegeTeams.DEFENDERS ? BossEvent.BossBarColor.RED : BossEvent.BossBarColor.BLUE;
            case CONTESTED -> BossEvent.BossBarColor.WHITE;
            case SECURING -> BossEvent.BossBarColor.GREEN;
            case RECAPTURE_DISABLED -> BossEvent.BossBarColor.YELLOW;
        };
    }

    @Override
    public @NotNull CapturingStateSidebarBlink getBlink() {
        return this.blink;
    }
}
