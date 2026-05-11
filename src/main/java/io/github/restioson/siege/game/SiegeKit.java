package io.github.restioson.siege.game;

import com.google.common.collect.Iterators;
import io.github.restioson.siege.entity.SiegeKitStandEntity;
import io.github.restioson.siege.game.active.SiegePersonalResource;
import io.github.restioson.siege.game.active.SiegePlayer;
import io.github.restioson.siege.item.SiegeHorn;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Instruments;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import org.jetbrains.annotations.Nullable;
import xyz.nucleoid.plasmid.api.game.common.team.GameTeam;
import xyz.nucleoid.plasmid.api.util.ItemStackBuilder;

import java.util.*;
import java.util.stream.Stream;

public final class SiegeKit {
    public static final int KIT_SWAP_COOLDOWN = 10 * 20;
    public static final List<SiegeKit> KITS = new ArrayList<>();
    public static final SiegeKit SOLDIER = new SiegeKit(
            "soldier",
            Items.IRON_SWORD,
            List.of(
                    new KitEquipment(Items.LEATHER_HELMET),
                    new KitEquipment(Items.DIAMOND_CHESTPLATE),
                    new KitEquipment(Items.LEATHER_LEGGINGS),
                    new KitEquipment(Items.IRON_BOOTS),
                    new KitEquipment(Items.IRON_SWORD, EquipmentSlot.MAINHAND),
                    new KitEquipment(Items.STONE_AXE, EquipmentSlot.OFFHAND)
            ),
            List.of(
                    new KitResource(
                            Component.translatable("item.minecraft.golden_apple"),
                            Items.GOLDEN_APPLE,
                            SiegePersonalResource.GAPPLE,
                            1
                    )
            ),
            List.of()
    );
    public static final SiegeKit SHIELD_BEARER = new SiegeKit(
            "shield_bearer",
            Items.SHIELD,
            List.of(
                    new KitEquipment(Items.LEATHER_HELMET),
                    new KitEquipment(Items.IRON_CHESTPLATE),
                    new KitEquipment(Items.IRON_LEGGINGS),
                    new KitEquipment(Items.LEATHER_BOOTS),
                    new KitEquipment(Items.IRON_SWORD, EquipmentSlot.MAINHAND),
                    new KitEquipment(Items.WOODEN_AXE, EquipmentSlot.MAINHAND),
                    new KitEquipable() {
                        @Override
                        public EquipmentSlot getArmorStandSlot() {
                            return EquipmentSlot.OFFHAND;
                        }

                        @Override
                        public ItemStack buildItemStack(GameTeam team, HolderLookup.Provider lookup) {
                            return ItemStackBuilder.of(Items.SHIELD)
                                    .setUnbreakable()
                                    .set(DataComponents.BASE_COLOR, team.config().blockDyeColor())
                                    .build();
                        }
                    }
            ),
            List.of(),
            List.of(kitEffect(MobEffects.RESISTANCE))
    );
    public static final SiegeKit ARCHER = new SiegeKit(
            "archer",
            Items.BOW,
            List.of(
                    new KitEquipment(Items.LEATHER_HELMET),
                    new KitEquipment(Items.LEATHER_CHESTPLATE),
                    new KitEquipment(Items.LEATHER_LEGGINGS),
                    new KitEquipment(Items.LEATHER_BOOTS),
                    new KitEquipment(Items.STONE_SWORD, EquipmentSlot.OFFHAND),
                    new KitEquipment(Items.BOW, EquipmentSlot.MAINHAND),
                    new KitEquipment(Items.CROSSBOW)
            ),
            List.of(new KitResource(
                    Component.translatable("game.siege.kit.items.arrows"),
                    Items.ARROW,
                    SiegePersonalResource.ARROWS,
                    32
            )),
            List.of(kitEffect(MobEffects.SPEED))
    );
    public static final SiegeKit ENGINEER = new SiegeKit(
            "engineer",
            Items.IRON_SHOVEL,
            List.of(
                    new KitEquipment(Items.LEATHER_HELMET),
                    new KitEquipment(Items.IRON_CHESTPLATE),
                    new KitEquipment(Items.LEATHER_LEGGINGS),
                    new KitEquipment(Items.LEATHER_BOOTS),
                    new KitEquipment(Items.STONE_SWORD),
                    new KitEquipment(Items.WOODEN_AXE)
            ),
            List.of(
                    KitResource.PLANKS,
                    new KitResource(
                            Component.translatable("game.siege.kit.items.tnt"),
                            Items.TNT,
                            SiegePersonalResource.TNT,
                            EquipmentSlot.MAINHAND,
                            2
                    )
            ),
            List.of()
    );
    public static final SiegeKit CAPTAIN = new SiegeKit(
            "captain",
            Items.GOAT_HORN,
            List.of(
                    new KitEquipment(Items.RED_BANNER, Items.BLUE_BANNER, EquipmentSlot.HEAD, EquipmentSlot.HEAD),
                    new KitEquipment(Items.GOLDEN_CHESTPLATE),
                    new KitEquipment(Items.LEATHER_LEGGINGS),
                    new KitEquipment(Items.GOLDEN_BOOTS),
                    new KitEquipment(Items.STONE_SWORD, EquipmentSlot.MAINHAND),
                    new KitEquipable() {
                        @Override
                        public EquipmentSlot getArmorStandSlot() {
                            return EquipmentSlot.OFFHAND;
                        }

                        @Override
                        public ItemStack buildItemStack(GameTeam team, HolderLookup.Provider lookup) {
                            return SiegeHorn.getStack(lookup,
                                    team == SiegeTeams.DEFENDERS ? Instruments.SING_GOAT_HORN :
                                            Instruments.SEEK_GOAT_HORN,
                                    List.of(
                                            new MobEffectInstance(MobEffects.STRENGTH, 10 * 20),
                                            new MobEffectInstance(MobEffects.SPEED, 10 * 20)
                                    )
                            );
                        }
                    }
            ),
            List.of(),
            List.of()
    );
    public static Item KIT_SELECT_ITEM = Items.COMPASS;
    public final Item icon;
    private final List<KitEquipable> equipment;
    private final List<AbstractKitResource> resources;
    private final List<MobEffectInstance> statusEffects;
    private final String id;

    private SiegeKit(String id, Item icon, List<KitEquipable> equipment, List<AbstractKitResource> resources,
                     List<MobEffectInstance> statusEffects) {
        this.id = id;
        this.equipment = Stream.concat(equipment.stream(), Stream.of(KitEquipment.KIT_SELECT)).toList();
        this.resources = Stream.concat(resources.stream(), Stream.of(KitResource.STEAK, KitResource.FIREWORK)).toList();
        this.statusEffects = statusEffects;
        this.icon = icon;

        KITS.add(this);
    }

    private static MobEffectInstance kitEffect(Holder<MobEffect> effect) {
        return new MobEffectInstance(effect, -1, 0, false, false, true);
    }

    private static Component restockMessage(List<RestockResult> restockResults, long time, boolean isEquip) {
        var text = Component.empty();

        if (restockResults.stream().allMatch(RestockResult::success)) {
            if (isEquip) {
                return Component.empty();
            }

            text.append(Component.literal("Successfully restocked ").withStyle(ChatFormatting.DARK_GREEN));
        } else if (restockResults.stream().noneMatch(RestockResult::success)) {
            text.append(Component.literal("Failed to restock ").withStyle(ChatFormatting.RED));
        } else {
            text.append(Component.literal("Partially restocked ").withStyle(ChatFormatting.YELLOW));
        }

        boolean first = true;
        var iter = restockResults.iterator();
        while (iter.hasNext()) {
            var result = iter.next();
            if (!first) {
                if (!iter.hasNext()) {
                    text.append(Component.literal(" and "));
                } else {
                    text.append(Component.literal(", "));
                }
            }
            first = false;

            var colour = result.success ? ChatFormatting.DARK_GREEN : ChatFormatting.RED;

            var restock = result.current == 0 && result.resource != null;
            var restockingIn = restock ? String.format(" (more in %ss)", result.resource.getNextRefreshSecs(time)) : "";

            text.append(result.name.copy().withStyle(colour));

            if (!result.success) {
                text.append(Component.literal(restockingIn).withStyle(colour));
            } else if (result.max != 0) {
                text.append(Component.literal(String.format(" (%d/%d left)", result.current, result.max)).withStyle(colour));
            }
        }

        return text;
    }

    public static ItemStack kitSelectItemStack() {
        return KitEquipment.KIT_SELECT.buildItemStack(null, null);
    }

    public void equipArmourStand(SiegeKitStandEntity stand) {
        var team = stand.getGameTeam();

        for (var item : this.equipment) {
            ItemStack stack = item.buildItemStack(team, stand.registryAccess());
            EquipmentSlot slot;
            if (item.getArmorStandSlot() != null) {
                slot = item.getArmorStandSlot();
            } else if (stack.has(DataComponents.EQUIPPABLE)) {
                slot = Objects.requireNonNull(stack.get(DataComponents.EQUIPPABLE)).slot();
            } else {
                continue;
            }

            stand.setItemSlot(slot, item.buildItemStack(team, stand.registryAccess()));
        }

        for (var item : this.resources) {
            if (item.equipmentSlot() != null) {
                stand.setItemSlot(item.equipmentSlot(), item.itemStackBuilder(team).build());
            }
        }
    }

    public void returnResources(ServerPlayer player, SiegePlayer participant) {
        var inventory = player.getInventory();

        for (var it = Iterators.concat(inventory.getNonEquipmentItems().iterator(), Iterators.singletonIterator(player.getOffhandItem())); it.hasNext(); ) {
            var stack = it.next();
            for (var resource : this.resources) {
                if (resource.resource() == null) {
                    continue;
                }

                if (resource.itemForTeam(participant.team) == stack.getItem()) {
                    participant.incrementResource(resource.resource(), stack.getCount());
                    break;
                }
            }
        }


        inventory.clearContent();
    }

    public void equipPlayer(ServerPlayer player, SiegePlayer participant, SiegeConfig config, long time) {
        participant.kit.returnResources(player, participant);
        participant.kit = this;

        var inventory = player.getInventory();
        var team = participant.team;

        for (var item : this.equipment) {
            var stack = item.buildItemStack(team, player.registryAccess());
            if (item.getPlayerSlot() != null) {
                player.setItemSlot(item.getPlayerSlot(), stack);
            } else if (stack.has(DataComponents.EQUIPPABLE)) {
                player.setItemSlot(Objects.requireNonNull(stack.get(DataComponents.EQUIPPABLE)).slot(), stack);
            } else {
                inventory.placeItemBackInInventory(stack);
            }
        }

        this.maybeGiveEnderPearl(player, participant, config);

        var result = this.restock(player, participant, time, true);
        if (!result.getSiblings().isEmpty()) {
            player.sendSystemMessage(result.copy().withStyle(ChatFormatting.BOLD), true);
        }

        player.removeAllEffects();
        for (var statusEffect : this.statusEffects) {
            player.addEffect(new MobEffectInstance(statusEffect)); // Copy
        }
    }

    private void maybeGiveEnderPearl(ServerPlayer player, SiegePlayer participant, SiegeConfig config) {
        if (config.hasEnderPearl(participant.team) && player.getInventory().countItem(Items.ENDER_PEARL) == 0) {
            player.getInventory()
                    .add(ItemStackBuilder.of(Items.ENDER_PEARL)
                            .setCount(1)
                            .setName(Component.literal("Warp to Front Lines"))
                            .addEnchantment(null, 1)
                            .addLore(Component.literal("This ender pearl will take you"))
                            .addLore(Component.literal("to a flag in need of assistance!"))
                            .build());
        }
    }

    public Component restock(ServerPlayer player, SiegePlayer participant, long time) {
        return this.restock(player, participant, time, false);
    }

    private Component restock(ServerPlayer player, SiegePlayer participant, long time, boolean isEquip) {
        var results = this.resources.stream().map(resource -> resource.restock(player, participant)).toList();
        return restockMessage(results, time, isEquip);
    }

    public Component getName() {
        return Component.translatable(String.format("game.siege.kit.kits.%s", this.id));
    }

    public Component[] getDescription() {
        return new Component[]{
                Component.translatable(String.format("game.siege.kit.kits.%s.desc.1", this.id)),
                Component.translatable(String.format("game.siege.kit.kits.%s.desc.2", this.id))
        };
    }

    public interface KitEquipable {
        @Nullable
        default EquipmentSlot getArmorStandSlot() {
            return null;
        }

        @Nullable
        default EquipmentSlot getPlayerSlot() {
            return null;
        }

        ItemStack buildItemStack(GameTeam team, HolderLookup.Provider lookup);
    }

    public interface AbstractKitResource {
        default RestockResult restock(ServerPlayer player, SiegePlayer participant) {
            var inventory = player.getInventory();
            var team = participant.team;
            var item = this.itemForTeam(team);
            var resource = this.resource();

            int required = this.max() - inventory.countItem(item);
            int toGive = resource != null ? participant.tryDecrementResource(resource, required) : required;

            var stack = this.itemStackBuilder(team).setCount(toGive).build();

            if (this.equipmentSlot() != null && required == this.max()) {
                player.setItemSlot(this.equipmentSlot(), stack);
            } else {
                inventory.placeItemBackInInventory(stack);
            }

            if (resource != null) {
                return new RestockResult(
                        participant.getResourceAmount(resource),
                        resource.max,
                        required == 0 || toGive > 0,
                        resource,
                        this.name()
                );
            } else {
                return new RestockResult(0, 0, true, null, this.name());
            }
        }

        Component name();

        int max();

        Item itemForTeam(GameTeam team);

        ItemStackBuilder itemStackBuilder(GameTeam team);

        @Nullable
        SiegePersonalResource resource();

        default @Nullable EquipmentSlot equipmentSlot() {
            return null;
        }
    }

    private record KitEquipment(Item attackerItem, Item defenderItem, List<EnchantmentInstance> enchantments,
                                @Nullable EquipmentSlot armourStandSlot, @Nullable EquipmentSlot playerSlot)
            implements KitEquipable {
        @SuppressWarnings("Convert2Lambda") // That would be hard to understand
        public final static KitEquipable KIT_SELECT = new KitEquipable() {
            @Override
            public ItemStack buildItemStack(@Nullable GameTeam team, HolderLookup.Provider lookup) {
                return ItemStackBuilder.of(KIT_SELECT_ITEM)
                        .setCount(1)
                        .setName(Component.literal("Kit Select"))
                        .set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
                        .addLore(Component.literal("This compass allows you"))
                        .addLore(Component.literal("to change your kit!"))
                        .build();
            }
        };

        public KitEquipment(Item item) {
            this(item, item, List.of(), null, null);
        }

        public KitEquipment(Item item, EquipmentSlot armourStandSlot) {
            this(item, item, List.of(), armourStandSlot, null);
        }

        public KitEquipment(Item attackerItem, Item defenderItem, EquipmentSlot armourStandSlot,
                            EquipmentSlot playerSlot) {
            this(attackerItem, defenderItem, List.of(), armourStandSlot, playerSlot);
        }

        public Item itemForTeam(GameTeam team) {
            return team == SiegeTeams.DEFENDERS ? this.defenderItem : this.attackerItem;
        }

        @Override
        public EquipmentSlot getArmorStandSlot() {
            return this.armourStandSlot;
        }

        @Override
        public EquipmentSlot getPlayerSlot() {
            return this.playerSlot;
        }

        public ItemStack buildItemStack(GameTeam team, HolderLookup.Provider lookup) {
            var builder = ItemStackBuilder.of(this.itemForTeam(team))
                    .setCount(1)
                    .setUnbreakable()
                    .setDyeColor(team.config().dyeColor().getValue());

            for (var enchantment : this.enchantments) {
                builder.addEnchantment(enchantment.enchantment(), enchantment.level());
            }

            return builder.build();
        }
    }

    private static final class Firework implements AbstractKitResource {
        @Override
        public Component name() {
            return Component.translatable("game.siege.kit.items.flare");
        }

        @Override
        public int max() {
            return 5;
        }

        @Override
        public Item itemForTeam(GameTeam team) {
            return Items.FIREWORK_ROCKET; // All fireworks are the same Item
        }

        @Override
        public ItemStackBuilder itemStackBuilder(GameTeam team) {
            return ItemStackBuilder.firework(
                    team.config().fireworkColor().getValue(),
                    2,
                    FireworkExplosion.Shape.SMALL_BALL
            );
        }

        @Override
        public SiegePersonalResource resource() {
            return SiegePersonalResource.FLARES;
        }
    }

    /**
     * A restockable item in a kit (e.g. arrows)
     */
    public record KitResource(@Override Component name, Item attackerItem, Item defenderItem,
                              @Override @Nullable SiegePersonalResource resource,
                              @Override @Nullable EquipmentSlot equipmentSlot, @Override int max)
            implements AbstractKitResource {
        public static final KitResource STEAK =
                new KitResource(Component.translatable("item.minecraft.cooked_beef"), Items.COOKED_BEEF, null, null, 10);
        public static final AbstractKitResource FIREWORK = new Firework();
        public static final KitResource PLANKS = new KitResource(
                Component.translatable("game.siege.kit.items.wood"),
                Items.CHERRY_PLANKS,
                Items.BIRCH_PLANKS,
                SiegePersonalResource.WOOD,
                EquipmentSlot.OFFHAND,
                16
        );

        public KitResource(Component name, Item item, @Nullable SiegePersonalResource resource, EquipmentSlot equipmentSlot,
                           int max) {
            this(name, item, item, resource, equipmentSlot, max);
        }

        public KitResource(Component name, Item item, @Nullable SiegePersonalResource resource, int max) {
            this(name, item, item, resource, null, max);
        }

        public ItemStackBuilder itemStackBuilder(GameTeam team) {
            return ItemStackBuilder.of(this.itemForTeam(team));
        }

        @Override
        public Item itemForTeam(GameTeam team) {
            return team == SiegeTeams.DEFENDERS ? this.defenderItem : this.attackerItem;
        }
    }

    /**
     * The result of trying to restock a kit.
     *
     * @param current  The current amount of {@link SiegePersonalResource} that the player has
     * @param max      The max amount of {@link SiegePersonalResource} that the player can have
     * @param success  Whether the restocking was successful
     * @param resource The {@link SiegePersonalResource} that the player tried to restock
     * @param name     The name of the item that they tried to restock. This isn't the same as the
     *                 {@link SiegePersonalResource}'s name, because arrows and wood both restock from the same pool,
     *                 for
     *                 example.
     */
    public record RestockResult(int current, int max, boolean success, SiegePersonalResource resource, Component name) {
    }
}
