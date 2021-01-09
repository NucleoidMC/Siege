package io.github.restioson.siege.game.map;

import io.github.restioson.siege.game.SiegeKit;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;
import xyz.nucleoid.plasmid.game.player.GameTeam;

public class SiegeKitStandLocation {
    @Nullable
    public final GameTeam team;
    @Nullable
    public final SiegeFlag controllingFlag;
    public final Vec3d pos;
    public final float yaw;
    public final SiegeKit type;

    public SiegeKitStandLocation(@Nullable GameTeam team, @Nullable SiegeFlag flag, Vec3d pos, SiegeKit type, float yaw) {
        this.team = team;
        this.pos = pos;
        this.controllingFlag = flag;
        this.type = type;
        this.yaw = yaw;
    }
}
