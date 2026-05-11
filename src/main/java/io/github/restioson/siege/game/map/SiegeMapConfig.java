package io.github.restioson.siege.game.map;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.Identifier;

public record SiegeMapConfig(
        Identifier templateId,
        int attackerSpawnAngle
) {
    public static final Codec<SiegeMapConfig> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Identifier.CODEC.fieldOf("id").forGetter(SiegeMapConfig::templateId),
            Codec.INT.fieldOf("attacker_spawn_angle").forGetter(SiegeMapConfig::attackerSpawnAngle)
    ).apply(instance, SiegeMapConfig::new));
}
