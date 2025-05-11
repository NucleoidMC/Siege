package io.github.restioson.siege.item;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import eu.pb4.polymer.core.api.item.PolymerItem;
import io.github.restioson.siege.Siege;
import io.github.restioson.siege.game.active.SiegeActive;
import io.github.restioson.siege.game.active.SiegePlayer;
import net.minecraft.entity.AreaEffectCloudEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.*;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.particle.EntityEffectParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.InstrumentTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.stat.Stats;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import net.minecraft.world.event.GameEvent;
import org.jetbrains.annotations.Nullable;
import xyz.nucleoid.packettweaker.PacketContext;
import xyz.nucleoid.stimuli.event.EventResult;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public class SiegeHorn extends GoatHornItem implements PolymerItem {
    private static final int COOLDOWN_TICKS = 30 * 20;
    private static final int SOUND_RADIUS = 64;
    private static final int EFFECT_RADIUS = 15;
    public SiegeHorn(Settings settings) {
        super(settings);
    }

    public static ItemStack getStack(RegistryWrapper.WrapperLookup lookup, RegistryKey<Instrument> instrument, List<StatusEffectInstance> effects) {
        ItemStack stack = SiegeHorn.getStackForInstrument(SiegeItems.HORN, lookup.getOrThrow(RegistryKeys.INSTRUMENT).getOrThrow(instrument));

        stack.set(SiegeItems.HORN_DATA, effects);

        return stack;
    }

    public static ActionResult onUse(SiegeActive active, ServerPlayerEntity userPlayer, SiegePlayer user, ItemStack stack, Hand hand) {
        var result = stack.use(active.world, userPlayer, hand);

        if (!result.isAccepted()) {
            return result; // Fail early
        }

        for (var effect : stack.getOrDefault(SiegeItems.HORN_DATA, List.<StatusEffectInstance>of())) {
            for (var entry : active.participants.entrySet()) {
                var participant = entry.getValue();
                var player = entry.getKey().getEntity(active.world);

                if (participant.team != user.team || player == null || !player.getBlockPos().isWithinDistance(userPlayer.getPos(), EFFECT_RADIUS)) {
                    continue;
                }

                player.addStatusEffect(new StatusEffectInstance(effect)); // Copy effect
            }
        }

        AreaEffectCloudEntity aoeCloud = new AreaEffectCloudEntity(
                active.world,
                userPlayer.getX(),
                userPlayer.getY(),
                userPlayer.getZ()
        );
        aoeCloud.setParticleType(EntityEffectParticleEffect.create(ParticleTypes.ENTITY_EFFECT, user.team.config().fireworkColor().getRgb()));
        aoeCloud.setRadius(EFFECT_RADIUS);
        aoeCloud.setDuration(1);
        active.world.spawnEntity(aoeCloud);

        var cooldownMgr = userPlayer.getItemCooldownManager();
        cooldownMgr.set(stack, COOLDOWN_TICKS);

        return result;
    }

    // TODO HACK: copied from vanilla to change distance. Really we should use a custom instrument
    private static void playSound(World world, PlayerEntity player, Instrument instrument) {
        SoundEvent soundEvent = instrument.soundEvent().value();
        float f = SOUND_RADIUS / 16.0F;
        world.playSoundFromEntity(player, player, soundEvent, SoundCategory.RECORDS, f, 1.0F);
        world.emitGameEvent(GameEvent.INSTRUMENT_PLAY, player.getPos(), GameEvent.Emitter.of(player));
    }

    // TODO HACK: copied from vanilla to override playSound
    @Override
    public ActionResult use(World world, PlayerEntity user, Hand hand) {
        ItemStack itemStack = user.getStackInHand(hand);
        Optional<? extends RegistryEntry<Instrument>> optional = this.getInstrument(itemStack, user.getRegistryManager());
        if (optional.isPresent()) {
            Instrument instrument = (Instrument) ((RegistryEntry<?>) optional.get()).value();
            user.setCurrentHand(hand);
            playSound(world, user, instrument);
            user.incrementStat(Stats.USED.getOrCreateStat(this));
            return ActionResult.CONSUME;
        } else {
            return ActionResult.FAIL;
        }
    }

    @Override
    public @Nullable Identifier getPolymerItemModel(ItemStack stack, PacketContext context) {
        return null;
    }

    @Override
    public Item getPolymerItem(ItemStack itemStack, PacketContext context) {
        return Items.GOAT_HORN;
    }
}
