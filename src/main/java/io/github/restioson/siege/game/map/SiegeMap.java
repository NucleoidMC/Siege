package io.github.restioson.siege.game.map;

import io.github.restioson.siege.game.SiegeTeams;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import xyz.nucleoid.plasmid.game.player.GameTeam;
import xyz.nucleoid.plasmid.map.template.MapTemplate;
import xyz.nucleoid.plasmid.map.template.TemplateChunkGenerator;
import xyz.nucleoid.plasmid.util.BlockBounds;

import java.util.ArrayList;
import java.util.List;

public class SiegeMap {
    private final MapTemplate template;
    public final List<SiegeFlag> flags = new ArrayList<>();
    public final List<SiegeKitStandLocation> kitStands = new ArrayList<>();
    public final int attackerSpawnAngle;
    public final BlockBounds bounds;
    public BlockBounds waitingSpawn = BlockBounds.EMPTY;
    public List<BlockBounds> noBuildRegions = new ArrayList<>();
    public List<SiegeGate> gates = new ArrayList<>();
    public BlockBounds attackerFirstSpawn;
    public BlockBounds defenderFirstSpawn;

    private final LongSet protectedBlocks = new LongOpenHashSet();

    public SiegeMap(MapTemplate template, int attackerSpawnAngle) {
        this.template = template;
        this.attackerSpawnAngle = attackerSpawnAngle;
        this.bounds = template.getBounds();
    }

    public BlockBounds getFirstSpawn(GameTeam team) {
        if (team == SiegeTeams.ATTACKERS) {
            return this.attackerFirstSpawn;
        } else {
            return this.defenderFirstSpawn;
        }
    }

    public void setWaitingSpawn(BlockBounds bounds) {
        this.waitingSpawn = bounds;
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
}
