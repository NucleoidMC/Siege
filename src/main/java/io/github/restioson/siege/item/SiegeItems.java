package io.github.restioson.siege.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import eu.pb4.polymer.core.api.item.PolymerCreativeModeTabUtils;
import eu.pb4.polymer.core.api.other.PolymerComponent;
import io.github.restioson.siege.Siege;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import java.util.List;
import java.util.function.Function;

public final class SiegeItems {
    public static final Item HORN = register("captains_horn", SiegeHorn::new);
    public static final DataComponentType<List<MobEffectInstance>> HORN_DATA = Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE, Identifier.fromNamespaceAndPath(Siege.ID, "horn_data"),
            DataComponentType.<List<MobEffectInstance>>builder().persistent(MobEffectInstance.CODEC.listOf()).build());

    public static final CreativeModeTab ITEM_GROUP = PolymerCreativeModeTabUtils.builder()
            .title(Component.translatable("gameType.siege.siege"))
            .icon(HORN::getDefaultInstance)
            .displayItems((context, entries) -> entries.accept(HORN))
            .build();

    @SuppressWarnings("SameParameterValue") // Keep this general, just in case
    private static <T extends Item> T register(String path, Function<Item.Properties, T> item) {
        return Registry.register(BuiltInRegistries.ITEM, Identifier.fromNamespaceAndPath(Siege.ID, path), item.apply(new Item.Properties().setId(ResourceKey.create(Registries.ITEM, Identifier.fromNamespaceAndPath(Siege.ID, path)))));
    }

    public static void register() {
        PolymerCreativeModeTabUtils.registerPolymerCreativeModeTab(Identifier.fromNamespaceAndPath(Siege.ID, "general"), ITEM_GROUP);
        PolymerComponent.registerDataComponent(HORN_DATA);
    }
}
