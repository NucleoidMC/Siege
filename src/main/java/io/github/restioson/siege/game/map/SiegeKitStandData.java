package io.github.restioson.siege.game.map;

import io.github.restioson.siege.game.SiegeKit;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import xyz.nucleoid.plasmid.api.game.common.team.GameTeam;

public record SiegeKitStandData(
        @Nullable GameTeam team,
        @Nullable SiegeFlag flag,
        Vec3 pos,
        SiegeKit type,
        float yaw
) {
}
