package io.github.restioson.siege.game.active;

import eu.pb4.sgui.api.elements.GuiElementInterface;
import eu.pb4.sgui.api.gui.SimpleGui;
import io.github.restioson.siege.game.map.SiegeFlag;
import io.github.restioson.siege.game.map.SiegeMap;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import xyz.nucleoid.plasmid.game.common.team.GameTeam;
import xyz.nucleoid.plasmid.shop.ShopEntry;
import xyz.nucleoid.plasmid.util.ColoredBlocks;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

public final class WarpSelectionUi extends SimpleGui {
    private WarpSelectionUi(ServerPlayerEntity player, List<GuiElementInterface> selectors) {
        super(ScreenHandlerType.GENERIC_9X1, player, false);
        this.setTitle(Text.literal("Warp to a Point"));
        selectors.forEach(this::addSlot);
    }

    public static WarpSelectionUi create(ServerPlayerEntity player, SiegeMap map, GameTeam team, Consumer<SiegeFlag> select) {
        var selectors = selectors(player, map, team, select);
        return new WarpSelectionUi(player, selectors);
    }

    private static List<GuiElementInterface> selectors(ServerPlayerEntity player, SiegeMap map, GameTeam team, Consumer<SiegeFlag> select) {
        List<GuiElementInterface> selectors = new ArrayList<>();

        long time = player.getWorld().getTime();

        for (SiegeFlag flag : map.flags) {
            if (flag.team != team) {
                continue;
            }

            MutableText name = Text.literal(flag.name);
            name = name.formatted(team.config().chatFormatting());

            ItemStack icon = flag.icon;
            if (icon == null) {
                icon = new ItemStack(ColoredBlocks.wool(team.config().blockDyeColor()));
            }

            if (flag.isFrontLine(time)) {
                icon.addEnchantment(null, 0);
            }

            selectors.add(ShopEntry.ofIcon(icon).withName(name).noCost().onBuy(p -> {
                select.accept(flag);
                p.closeHandledScreen();
            }));
        }

        return selectors;
    }
}
