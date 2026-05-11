package io.github.restioson.siege.game.active;

import eu.pb4.sgui.api.SguiUtils;
import eu.pb4.sgui.api.elements.GuiElement;
import eu.pb4.sgui.api.gui.GuiLike;
import eu.pb4.sgui.api.gui.SimpleGui;
import io.github.restioson.siege.game.SiegeKit;
import io.github.restioson.siege.game.map.SiegeFlag;
import io.github.restioson.siege.game.map.SiegeMap;
import org.jetbrains.annotations.Nullable;
import xyz.nucleoid.plasmid.api.game.common.team.GameTeam;
import xyz.nucleoid.plasmid.api.shop.ShopEntry;
import xyz.nucleoid.plasmid.api.util.ColoredBlocks;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;

public final class WarpSelectionUi extends SimpleGui {
    private final GuiLike previousUi;

    private WarpSelectionUi(ServerPlayer player, List<GuiElement> selectors, Component title) {
        super(MenuType.GENERIC_9x3, player, false);
        this.setTitle(title);
        selectors.forEach(this::addSlot);
        this.previousUi = SguiUtils.getCurrentGui(player);
    }

    public static WarpSelectionUi createFlagWarp(ServerPlayer player, SiegeMap map, GameTeam team,
                                                 Consumer<SiegeFlag> select) {
        var selectors = flagSelectors(player, map, team, select);
        return new WarpSelectionUi(player, selectors, Component.translatable("game.siege.warp.flag"));
    }

    public static WarpSelectionUi createKitSelect(ServerPlayer player, @Nullable SiegeKit selectedKit,
                                                  Consumer<SiegeKit> select) {
        var selectors = kitSelectors(selectedKit, select);
        return new WarpSelectionUi(player, selectors, Component.translatable("game.siege.warp.kit"));
    }

    private static List<GuiElement> kitSelectors(@Nullable SiegeKit selectedKit, Consumer<SiegeKit> select) {
        List<GuiElement> selectors = new ArrayList<>();

        for (SiegeKit kit : SiegeKit.KITS) {
            ItemStack icon = kit.icon.getDefaultInstance();

            if (selectedKit == kit) {
                icon.enchant(null, 0);
            }

            var entry = ShopEntry.ofIcon(icon)
                    .withName(kit.getName())
                    .noCost()
                    .onBuy(p -> {
                        select.accept(kit);
                    });

            for (var desc : kit.getDescription()) {
                entry.addLore(desc);
            }

            selectors.add(entry);
        }

        return selectors;
    }

    private static List<GuiElement> flagSelectors(ServerPlayer player, SiegeMap map, GameTeam team,
                                                           Consumer<SiegeFlag> select) {
        List<GuiElement> selectors = new ArrayList<>();

        long time = player.level().getGameTime();

        for (SiegeFlag flag : map.flags) {
            if (flag.team != team) {
                continue;
            }

            MutableComponent name = Component.literal(flag.name);
            name = name.withStyle(team.config().chatFormatting());

            ItemStack icon = flag.icon;
            if (icon == null) {
                icon = new ItemStack(ColoredBlocks.wool(team.config().blockDyeColor()));
            }

            if (flag.isFrontLine(time)) {
                icon.enchant(null, 0);
            }

            selectors.add(ShopEntry.ofIcon(icon).withName(name).noCost().onBuy(p -> {
                select.accept(flag);
                p.closeContainer();
            }));
        }

        return selectors;
    }

    @Override
    public void afterRemoval() {
        super.afterRemoval();
        if (this.previousUi != null) {
            this.previousUi.open();
        }
    }
}
