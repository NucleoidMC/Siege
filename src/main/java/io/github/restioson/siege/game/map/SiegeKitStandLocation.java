package io.github.restioson.siege.game.map;

import io.github.restioson.siege.game.SiegeKit;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;
import xyz.nucleoid.plasmid.game.common.team.GameTeam;

public final record SiegeKitStandLocation(
        @Nullable GameTeam team,
        @Nullable SiegeFlag flag,
        Vec3d pos,
        SiegeKit type,
        float yaw
) {
}
