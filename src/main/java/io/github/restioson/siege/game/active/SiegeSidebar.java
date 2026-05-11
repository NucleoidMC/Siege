package io.github.restioson.siege.game.active;

import io.github.restioson.siege.game.SiegeConfig;
import io.github.restioson.siege.game.SiegeTeams;
import io.github.restioson.siege.game.map.SiegeFlag;
import xyz.nucleoid.plasmid.api.game.common.GlobalWidgets;
import xyz.nucleoid.plasmid.api.game.common.team.GameTeam;
import xyz.nucleoid.plasmid.api.game.common.widget.SidebarWidget;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import static io.github.restioson.siege.game.active.capturing.CapturingStateSidebarBlink.NO_BLINK;

public final class SiegeSidebar {
    private static final int SORT_THREATENED = 0;
    private static final int SORT_ATTACKERS = 1;
    private static final int SORT_DEFENDERS = 2;

    private final SiegeActive game;
    private final SidebarWidget widget;
    private final SiegeConfig config;

    SiegeSidebar(SiegeActive game, GlobalWidgets widgets) {
        this.game = game;
        this.widget = widgets.addSidebar(Component.translatable("gameType.siege.siege")
                .withStyle(ChatFormatting.BOLD, ChatFormatting.GOLD, ChatFormatting.UNDERLINE));
        this.config = this.game.config;
    }

    private static int getSortIndex(SiegeFlag flag, long time) {
        if (flag.isFrontLine(time) || flag.captureProgressTicks > 0) {
            return SORT_THREATENED;
        } else if (flag.team == SiegeTeams.ATTACKERS) {
            return SORT_ATTACKERS;
        } else if (flag.team == SiegeTeams.DEFENDERS) {
            return SORT_DEFENDERS;
        }
        return -1;
    }

    public void update(long time) {
        this.widget.set(content -> {
            if (this.config.capturingGiveTimeSecs() > 0) {
                content.add(
                        Component.translatable("game.siege.quick.sidebar").withStyle(ChatFormatting.GOLD),
                        Component.literal(this.config.giveTimeFormatted()).withStyle(ChatFormatting.AQUA)
                );
                content.add(CommonComponents.EMPTY);
            }

            List<SiegeFlag> flags = new ArrayList<>(this.game.map.flags);
            flags.sort(Comparator.comparingInt(flag -> getSortIndex(flag, time)));

            long seconds = time / 20;
            boolean blink = seconds % 2 == 0;

            for (SiegeFlag flag : flags) {
                if (!flag.capturable) {
                    continue;
                }

                boolean italic = false;
                ChatFormatting color = flag.team.config().chatFormatting();

                var blinkType = flag.capturingState != null ? flag.capturingState.getBlink() : NO_BLINK;
                switch (blinkType) {
                    case NO_BLINK -> {
                    }
                    case OWNING_TEAM_TO_CAPTURING -> {
                        GameTeam blinkTeam = blink ? SiegeTeams.ATTACKERS : SiegeTeams.DEFENDERS;
                        color = blinkTeam.config().chatFormatting();
                        italic = blinkTeam != flag.team;
                    }
                    case OWNING_TEAM_TO_GREY -> color = blink ? color : ChatFormatting.GRAY;
                }

                var flagName = Component.literal(flag.name).withStyle(color);

                if (italic) {
                    flagName.withStyle(ChatFormatting.ITALIC);
                }

                int percent = (int) Math.floor(flag.captureFraction() * 100);

                Component line;
                boolean underAttack = flag.capturingState != null && flag.isFlagUnderAttack();
                if (underAttack || percent > 0) {
                    line = Component.literal("(" + percent + "%) ").append(flagName);
                } else if (flag.gateUnderAttack(time)) {
                    line = Component.literal("(!) ").append(flagName);
                } else {
                    line = flagName;
                }

                content.add(line);
            }
        });
    }
}
