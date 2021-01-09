package io.github.restioson.siege.game;

import io.github.restioson.siege.entity.SiegeKitStandEntity;
import io.github.restioson.siege.game.active.SiegePersonalResource;
import io.github.restioson.siege.game.active.SiegePlayer;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;
import xyz.nucleoid.plasmid.game.player.GameTeam;
import xyz.nucleoid.plasmid.util.ItemStackBuilder;

public enum SiegeKit {
    ARCHER(new LiteralText("Archer Kit")),
    SOLDIER(new LiteralText("Soldier Kit")),
    CONSTRUCTOR(new LiteralText("Constructor Kit")),
    SHIELD_BEARER(new LiteralText("Shield Bearer Kit")),
    DEMOLITIONER(new LiteralText("Demolitioner Kit"));

    public static int ARROWS = 16;
    public static int PLANKS = 12;
    public static int STEAK = 10;
    public static int TNT = 1;

    public final Text name;

    SiegeKit(Text name) {
        this.name = name;
    }

    public void equipArmourStand(SiegeKitStandEntity stand) {
        switch (this) {
            case ARCHER:
                this.giveArcherEquipment(stand, stand.getTeam());
                stand.equipStack(EquipmentSlot.OFFHAND, new ItemStack(Items.WOODEN_SWORD));
                stand.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
                break;
            case SOLDIER:
                this.giveSoldierEquipment(stand, stand.getTeam());
                stand.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
                stand.equipStack(EquipmentSlot.OFFHAND, new ItemStack(Items.STONE_AXE));
                break;
            case CONSTRUCTOR:
                this.giveConstructorEquipment(stand, stand.getTeam());
                stand.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.WOODEN_AXE));
                break;
            case SHIELD_BEARER:
                this.giveShieldEquipment(stand, stand.getTeam());
                stand.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.STONE_SWORD));
                break;
            case DEMOLITIONER:
                this.giveDemolitionerEquipment(stand, stand.getTeam());
                break;
        }
    }

    private void maybeGiveEnderPearl(ServerPlayerEntity player, SiegePlayer participant, SiegeConfig config) {
        if (config.defenderEnderPearl && participant.team == SiegeTeams.DEFENDERS && player.inventory.count(Items.ENDER_PEARL) == 0) {
            player.inventory.insertStack(
                    ItemStackBuilder.of(Items.ENDER_PEARL)
                            .setCount(1)
                            .setName(new LiteralText("Warp to Front Lines"))
                            .addEnchantment(Enchantments.LUCK_OF_THE_SEA, 1)
                            .addLore(new LiteralText("This ender pearl will take you"))
                            .addLore(new LiteralText("to a flag in need of assistance!"))
                            .build()
            );
        }
    }

    public @Nullable String restock(ServerPlayerEntity player, SiegePlayer participant, ServerWorld world, SiegeConfig config) {
        switch (participant.kit) {
            case ARCHER:
                int arrowsRequired = SiegeKit.ARROWS - player.inventory.count(Items.ARROW);
                int arrowsToGive = participant.tryDecrementResource(SiegePersonalResource.WOOD, arrowsRequired);
                player.inventory.offerOrDrop(world, ItemStackBuilder.of(Items.ARROW).setCount(arrowsToGive).build());

                if (arrowsRequired != 0 && arrowsToGive == 0) {
                    return "You have no more arrows right now!";
                }

                break;
            case CONSTRUCTOR:
                Item planks = SiegeTeams.planksForTeam(participant.team);
                int planksRequired = SiegeKit.PLANKS - player.inventory.count(planks);
                int planksToGive = participant.tryDecrementResource(SiegePersonalResource.WOOD, planksRequired);

                player.inventory.offerOrDrop(world, ItemStackBuilder.of(planks).setCount(planksToGive).build());
                break;
            case DEMOLITIONER:
                int tntRequired = SiegeKit.TNT - player.inventory.count(Items.TNT);
                int tntToGive = participant.tryDecrementResource(SiegePersonalResource.TNT, tntRequired);
                player.inventory.offerOrDrop(world, ItemStackBuilder.of(Items.TNT).setCount(tntToGive).build());

                if (tntRequired != 0 && tntToGive == 0) {
                    return "You have no TNT right now!";
                }

                break;
            default:
                break;
        }

        int steakRequired = SiegeKit.STEAK - player.inventory.count(Items.COOKED_BEEF);
        player.inventory.offerOrDrop(world, ItemStackBuilder.of(Items.COOKED_BEEF).setCount(steakRequired).build());

        this.maybeGiveEnderPearl(player, participant, config);

        return null;
    }

    public void equipPlayer(ServerPlayerEntity player, SiegePlayer participant, ServerWorld world, SiegeConfig config) {
        int wood = player.inventory.count(Items.ARROW) + player.inventory.count(SiegeTeams.planksForTeam(SiegeTeams.ATTACKERS))
                + player.inventory.count(SiegeTeams.planksForTeam(SiegeTeams.DEFENDERS));
        int tnt = player.inventory.count(Items.TNT);
        participant.incrementResource(SiegePersonalResource.WOOD, wood);
        participant.incrementResource(SiegePersonalResource.TNT, tnt);

        player.inventory.clear();
        player.clearStatusEffects();
        GameTeam team = participant.team;

        switch (this) {
            case ARCHER:
                this.giveArcherKit(player, participant);
                player.addStatusEffect(new StatusEffectInstance(StatusEffects.SPEED, Integer.MAX_VALUE, 0, false, false, true));
                break;
            case SOLDIER:
                this.giveSoldierKit(player, team);
                break;
            case CONSTRUCTOR:
                this.giveConstructorKit(player, participant);
                break;
            case SHIELD_BEARER:
                this.giveShieldKit(player, team);
                player.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, Integer.MAX_VALUE, 0, false, false, true));
                break;
            case DEMOLITIONER:
                this.giveDemolitionerKit(player, team);
                player.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, Integer.MAX_VALUE, 0, false, false, true));
                break;
        }

        player.inventory.insertStack(ItemStackBuilder.of(Items.COOKED_BEEF).setCount(STEAK).build());

        this.maybeGiveEnderPearl(player, participant, config);
        this.restock(player, participant, world, config);
    }

    // give{x}Equipment is for armour stands and players

    private void giveArcherEquipment(LivingEntity entity, GameTeam team) {
        entity.equipStack(EquipmentSlot.HEAD, ItemStackBuilder.of(Items.LEATHER_HELMET).setColor(team.getColor()).setUnbreakable().build());
        entity.equipStack(EquipmentSlot.CHEST, ItemStackBuilder.of(Items.LEATHER_CHESTPLATE).setColor(team.getColor()).setUnbreakable().build());
        entity.equipStack(EquipmentSlot.LEGS, ItemStackBuilder.of(Items.LEATHER_LEGGINGS).setColor(team.getColor()).setUnbreakable().build());
        entity.equipStack(EquipmentSlot.FEET, ItemStackBuilder.of(Items.LEATHER_BOOTS).setColor(team.getColor()).setUnbreakable().build());
    }

    private void giveSoldierEquipment(LivingEntity entity, GameTeam team) {
        entity.equipStack(EquipmentSlot.HEAD, ItemStackBuilder.of(Items.LEATHER_HELMET).setColor(team.getColor()).setUnbreakable().build());
        entity.equipStack(EquipmentSlot.CHEST, ItemStackBuilder.of(Items.DIAMOND_CHESTPLATE).setUnbreakable().build());
        entity.equipStack(EquipmentSlot.LEGS, ItemStackBuilder.of(Items.LEATHER_LEGGINGS).setColor(team.getColor()).setUnbreakable().build());
        entity.equipStack(EquipmentSlot.FEET, ItemStackBuilder.of(Items.IRON_BOOTS).setUnbreakable().build());
    }

    private void giveConstructorEquipment(LivingEntity entity, GameTeam team) {
        entity.equipStack(EquipmentSlot.HEAD, ItemStackBuilder.of(Items.LEATHER_HELMET).setColor(team.getColor()).setUnbreakable().build());
        entity.equipStack(EquipmentSlot.CHEST, ItemStackBuilder.of(Items.IRON_CHESTPLATE).setUnbreakable().build());
        entity.equipStack(EquipmentSlot.LEGS, ItemStackBuilder.of(Items.LEATHER_LEGGINGS).setColor(team.getColor()).setUnbreakable().build());
        entity.equipStack(EquipmentSlot.FEET, ItemStackBuilder.of(Items.LEATHER_BOOTS).setColor(team.getColor()).setUnbreakable().build());
        entity.equipStack(EquipmentSlot.OFFHAND, new ItemStack(SiegeTeams.planksForTeam(team)));
    }

    private void giveShieldEquipment(LivingEntity entity, GameTeam team) {
        entity.equipStack(EquipmentSlot.HEAD, ItemStackBuilder.of(Items.LEATHER_HELMET).setColor(team.getColor()).setUnbreakable().build());
        entity.equipStack(EquipmentSlot.CHEST, ItemStackBuilder.of(Items.IRON_CHESTPLATE).setUnbreakable().build());
        entity.equipStack(EquipmentSlot.LEGS, ItemStackBuilder.of(Items.IRON_LEGGINGS).setUnbreakable().build());
        entity.equipStack(EquipmentSlot.FEET, ItemStackBuilder.of(Items.LEATHER_BOOTS).setColor(team.getColor()).setUnbreakable().build());
        entity.equipStack(EquipmentSlot.OFFHAND, ItemStackBuilder.of(Items.SHIELD).setUnbreakable().build());
    }

    private void giveDemolitionerEquipment(LivingEntity entity, GameTeam team) {
        entity.equipStack(EquipmentSlot.HEAD, ItemStackBuilder.of(Items.LEATHER_HELMET).setColor(team.getColor()).setUnbreakable().build());
        entity.equipStack(EquipmentSlot.CHEST, ItemStackBuilder.of(Items.LEATHER_CHESTPLATE).setColor(team.getColor()).setUnbreakable().build());
        entity.equipStack(EquipmentSlot.LEGS, ItemStackBuilder.of(Items.LEATHER_LEGGINGS).setColor(team.getColor()).setUnbreakable().build());
        entity.equipStack(EquipmentSlot.FEET, ItemStackBuilder.of(Items.LEATHER_BOOTS).setColor(team.getColor()).setUnbreakable().build());
        entity.equipStack(EquipmentSlot.MAINHAND, ItemStackBuilder.of(Items.TNT).setUnbreakable().build());
    }

    // give{x}Kit is for players only

    private void giveArcherKit(PlayerEntity player, SiegePlayer participant) {
        this.giveArcherEquipment(player, participant.team);
        player.inventory.insertStack(ItemStackBuilder.of(Items.WOODEN_SWORD).setUnbreakable().build());
        player.inventory.insertStack(
                ItemStackBuilder.of(Items.BOW)
                        .addEnchantment(Enchantments.POWER, 1)
                        .setUnbreakable()
                        .build()
        );
    }

    private void giveSoldierKit(PlayerEntity player, GameTeam team) {
        this.giveSoldierEquipment(player, team);
        player.inventory.insertStack(ItemStackBuilder.of(Items.IRON_SWORD).setUnbreakable().build());
        player.inventory.insertStack(ItemStackBuilder.of(Items.STONE_AXE).setUnbreakable().build());
    }

    private void giveConstructorKit(PlayerEntity player, SiegePlayer participant) {
        this.giveConstructorEquipment(player, participant.team);
        player.inventory.insertStack(ItemStackBuilder.of(Items.WOODEN_SWORD).setUnbreakable().build());
        player.inventory.insertStack(ItemStackBuilder.of(Items.WOODEN_AXE).setUnbreakable().build());
        player.equipStack(EquipmentSlot.OFFHAND, new ItemStack(Items.AIR));
    }

    private void giveShieldKit(PlayerEntity player, GameTeam team) {
        this.giveShieldEquipment(player, team);
        player.inventory.insertStack(ItemStackBuilder.of(Items.STONE_SWORD).setUnbreakable().build());
    }

    private void giveDemolitionerKit(PlayerEntity player, GameTeam team) {
        this.giveDemolitionerEquipment(player, team);
        player.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.AIR));
    }
}
