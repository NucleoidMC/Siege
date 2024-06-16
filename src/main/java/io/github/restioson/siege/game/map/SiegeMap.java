package io.github.restioson.siege.game.map;

import io.github.restioson.siege.Siege;
import io.github.restioson.siege.entity.SiegeKitStandEntity;
import io.github.restioson.siege.game.SiegeSpawnLogic;
import io.github.restioson.siege.game.SiegeTeams;
import io.github.restioson.siege.game.active.SiegeActive;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import xyz.nucleoid.map_templates.BlockBounds;
import xyz.nucleoid.map_templates.MapTemplate;
import xyz.nucleoid.plasmid.game.common.team.GameTeam;
import xyz.nucleoid.plasmid.game.world.generator.TemplateChunkGenerator;

import java.util.ArrayList;
import java.util.List;

public class SiegeMap {
    private final SiegeMapConfig config;
    private final MapTemplate template;
    public final List<SiegeFlag> flags = new ArrayList<>();
    public final List<SiegeKitStandLocation> kitStands = new ArrayList<>();
    public SiegeSpawn waitingSpawn = null;
    public List<BlockBounds> noBuildRegions = new ArrayList<>();
    public List<SiegeGate> gates = new ArrayList<>();
    public SiegeSpawn attackerFirstSpawn;
    public SiegeSpawn defenderFirstSpawn;
    public long time;

    private final LongSet protectedBlocks = new LongOpenHashSet();

    public SiegeMap(SiegeMapConfig config, MapTemplate template) {
        this.config = config;
        this.template = template;
        this.time = 1000;
    }

    private static SiegeMap merge(SiegeActive active, SiegeMap oldMap, SiegeMap newMap) {
        var world = active.world;
        var gameSpace = active.gameSpace;

        for (var newFlag : newMap.flags) {
            oldMap.flags.stream().filter(f -> f.id.equals(newFlag.id)).findFirst()
                    .ifPresent(oldFlag -> {
                        newFlag.captureProgressTicks = oldFlag.captureProgressTicks;
                        newFlag.team = oldFlag.team;
                        newFlag.setTeamBlocks(world, newFlag.team);
                    });
        }

        for (var oldFlag : oldMap.flags) {
            oldFlag.captureBar.clearPlayers();
        }

        for (var newGate : newMap.gates) {
            oldMap.gates.stream().filter(g -> g.id.equals(newGate.id)).findFirst()
                    .ifPresent(oldGate -> {
                        newGate.bashedOpen = oldGate.bashedOpen;
                        newGate.health = oldGate.health;
                        newGate.openSlide = oldGate.openSlide;

                        if (newGate.bashedOpen) {
                            newGate.slider.setOpen(world);
                        } else {
                            newGate.slider.set(world, newGate.openSlide);
                        }
                    });
        }

        newMap.spawnKitStands(active);

        for (var player : gameSpace.getPlayers()) {
            if (!newMap.template.getBounds().contains(player.getBlockPos()) || player.isInsideWall()) {
                SiegeSpawnLogic.spawnPlayer(player, newMap.waitingSpawn, world);
            }
        }

        gameSpace.getPlayers().sendMessage(Text.literal("[Siege] Map reloaded!"));
        return newMap;
    }

    public void reload(SiegeActive active) {
        var world = active.world;
        var gameSpace = active.gameSpace;

        var server = world.getServer();

        long time = System.currentTimeMillis();
        var newMap = SiegeMapLoader.load(server, this.config);
        System.out.printf("Loading took %sms%n", System.currentTimeMillis() - time);

        time = System.currentTimeMillis();
        var diff = newMap.template.diff(this.template);
        System.out.printf("Diffing took %sms%n", System.currentTimeMillis() - time);

        Siege.LOGGER.info("Loaded new template with diff {}", diff);

        if (diff.needsRegeneration()) {
            Siege.LOGGER.info("Regenerating world");
            gameSpace.getWorlds().regenerate(world, newMap.asGenerator(server), newMap.template.getBounds().union(this.template.getBounds()));
        } else {
            Siege.LOGGER.debug("Diff does not warrant regeneration");
        }

        if (newMap.time != this.time) {
            Siege.LOGGER.info("Changing time from {} to {}", this.time, newMap.time);
            world.setTimeOfDay(newMap.time);
        } else {
            Siege.LOGGER.debug("Skipping changing time, as they are the same");
        }

        var merged = merge(active, this, newMap);
        Siege.LOGGER.info("Done reloading map!");

        active.map = merged;
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

    public void spawnKitStands(SiegeActive active) {
        for (SiegeKitStandLocation stand : this.kitStands) {
            SiegeKitStandEntity standEntity = new SiegeKitStandEntity(active.world, active, stand);
            active.world.spawnEntity(standEntity);

            if (standEntity.controllingFlag != null) {
                standEntity.controllingFlag.kitStands.add(standEntity);
            }
        }
    }
}
