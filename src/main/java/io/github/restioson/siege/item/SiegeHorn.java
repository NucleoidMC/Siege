package io.github.restioson.siege.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import eu.pb4.polymer.core.api.item.PolymerItem;
import io.github.restioson.siege.Siege;
import io.github.restioson.siege.game.active.SiegeActive;
import io.github.restioson.siege.game.active.SiegePlayer;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.AreaEffectCloud;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Instrument;
import net.minecraft.world.item.InstrumentItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.InstrumentComponent;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import org.jetbrains.annotations.Nullable;
import xyz.nucleoid.stimuli.event.EventResult;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public class SiegeHorn extends InstrumentItem implements PolymerItem {
    private static final int COOLDOWN_TICKS = 30 * 20;
    private static final int SOUND_RADIUS = 64;
    private static final int EFFECT_RADIUS = 15;
    public SiegeHorn(Properties settings) {
        super(settings);
    }

    public static ItemStack getStack(HolderLookup.Provider lookup, ResourceKey<Instrument> instrument, List<MobEffectInstance> effects) {
        ItemStack stack = SiegeHorn.create(SiegeItems.HORN, lookup.lookupOrThrow(Registries.INSTRUMENT).getOrThrow(instrument));

        stack.set(SiegeItems.HORN_DATA, effects);

        return stack;
    }

    public static InteractionResult onUse(SiegeActive active, ServerPlayer userPlayer, SiegePlayer user, ItemStack stack, InteractionHand hand) {
        var result = stack.use(active.world, userPlayer, hand);

        if (!result.consumesAction()) {
            return result; // Fail early
        }

        for (var effect : stack.getOrDefault(SiegeItems.HORN_DATA, List.<MobEffectInstance>of())) {
            for (var entry : active.participants.entrySet()) {
                var participant = entry.getValue();
                var player = entry.getKey().getEntity(active.world);

                if (participant.team != user.team || player == null || !player.blockPosition().closerToCenterThan(userPlayer.position(), EFFECT_RADIUS)) {
                    continue;
                }

                player.addEffect(new MobEffectInstance(effect)); // Copy effect
            }
        }

        AreaEffectCloud aoeCloud = new AreaEffectCloud(
                active.world,
                userPlayer.getX(),
                userPlayer.getY(),
                userPlayer.getZ()
        );
        aoeCloud.setCustomParticle(ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, user.team.config().fireworkColor().getValue()));
        aoeCloud.setRadius(EFFECT_RADIUS);
        aoeCloud.setDuration(1);
        active.world.addFreshEntity(aoeCloud);

        var cooldownMgr = userPlayer.getCooldowns();
        cooldownMgr.addCooldown(stack, COOLDOWN_TICKS);

        return result;
    }

    // TODO HACK: copied from vanilla to change distance. Really we should use a custom instrument
    private static void play(Level world, Player player, Instrument instrument) {
        SoundEvent soundEvent = instrument.soundEvent().value();
        float f = SOUND_RADIUS / 16.0F;
        world.playSound(player, player, soundEvent, SoundSource.RECORDS, f, 1.0F);
        world.gameEvent(GameEvent.INSTRUMENT_PLAY, player.position(), GameEvent.Context.of(player));
    }

    // TODO HACK: copied from vanilla to override playSound
    @Override
    public InteractionResult use(Level world, Player user, InteractionHand hand) {
        ItemStack itemStack = user.getItemInHand(hand);
        Optional<? extends Holder<Instrument>> optional = Optional.ofNullable(itemStack.get(DataComponents.INSTRUMENT)).map(InstrumentComponent::instrument);
        if (optional.isPresent()) {
            Instrument instrument = (Instrument) ((Holder<?>) optional.get()).value();
            user.startUsingItem(hand);
            play(world, user, instrument);
            user.awardStat(Stats.ITEM_USED.get(this));
            return InteractionResult.CONSUME;
        } else {
            return InteractionResult.FAIL;
        }
    }

    @Override
    public @Nullable Identifier getPolymerItemModel(ItemStack stack, PacketContext context, HolderLookup.Provider provider) {
        return null;
    }

    @Override
    public Item getPolymerItem(ItemStack itemStack, PacketContext context) {
        return Items.GOAT_HORN;
    }
}
