package io.github.restioson.siege.game.map;

import io.github.restioson.siege.duck.SiegeVehicleExt;
import io.github.restioson.siege.entity.SiegeKitStandEntity;
import io.github.restioson.siege.game.SiegeTeams;
import io.github.restioson.siege.game.active.SiegeActive;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.vehicle.boat.Boat;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.entity.EntityTypeTest;
import xyz.nucleoid.map_templates.BlockBounds;
import xyz.nucleoid.map_templates.MapTemplate;
import xyz.nucleoid.plasmid.api.game.common.team.GameTeam;
import xyz.nucleoid.plasmid.api.game.level.generator.TemplateChunkGenerator;

import java.util.ArrayList;
import java.util.List;

public class SiegeMap {
    private final MapTemplate template;
    public final List<SiegeFlag> flags = new ArrayList<>();
    public final List<SiegeKitStandData> kitStands = new ArrayList<>();
    public SiegeSpawn waitingSpawn = null;
    public List<BlockBounds> noBuildRegions = new ArrayList<>();
    public List<SiegeGate> gates = new ArrayList<>();
    public SiegeSpawn attackerFirstSpawn;
    public SiegeSpawn defenderFirstSpawn;
    public long time;

    private final LongSet protectedBlocks = new LongOpenHashSet();

    public SiegeMap(MapTemplate template) {
        this.template = template;
        this.time = 1000;
    }

    public SiegeSpawn getFirstSpawn(GameTeam team) {
        if (team == SiegeTeams.ATTACKERS) {
            return this.attackerFirstSpawn;
        } else {
            return this.defenderFirstSpawn;
        }
    }

    public void setWaitingSpawn(SiegeSpawn spawn) {
        this.waitingSpawn = spawn;
    }

    public void addProtectedBlock(long pos) {
        this.protectedBlocks.add(pos);
    }

    public boolean isProtectedBlock(BlockPos pos) {
        return this.isProtectedBlock(pos.asLong());
    }

    public boolean isProtectedBlock(long pos) {
        return this.protectedBlocks.contains(pos);
    }

    public ChunkGenerator asGenerator(MinecraftServer server) {
        return new TemplateChunkGenerator(server, this.template);
    }

    public void startGame(SiegeActive active) {
        for (SiegeKitStandData stand : this.kitStands) {
            SiegeKitStandEntity standEntity = new SiegeKitStandEntity(active, stand);
            active.world.addFreshEntity(standEntity);

            if (standEntity.controllingFlag != null) {
                standEntity.controllingFlag.kitStands.add(standEntity);
            }
        }

        var vehicles = active.world.getEntities(EntityTypeTest.forClass(Boat.class), e -> true);
        for (var vehicle : vehicles) {
            ((SiegeVehicleExt) vehicle).siege$setInSiegeGame();
        }
    }
}
