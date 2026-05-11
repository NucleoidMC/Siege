package io.github.restioson.siege.game.active;

import io.github.restioson.siege.game.SiegeKit;
import io.github.restioson.siege.game.map.SiegeSpawn;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import xyz.nucleoid.plasmid.api.util.PlayerRef;

public class WarpingPlayer {
    final PlayerRef player;
    final Vec3 pos;
    final SiegeSpawn destination;
    final long startTime;
    @Nullable
    final SiegeKit newKit;

    public WarpingPlayer(ServerPlayer player, SiegeSpawn destination, long time, @Nullable SiegeKit newKit) {
        this.player = PlayerRef.of(player);
        this.pos = player.position();
        this.destination = destination;
        this.startTime = time;
        this.newKit = newKit;
    }
}
