package io.github.restioson.siege.game.map;

import io.github.restioson.siege.Siege;
import io.github.restioson.siege.game.SiegeKit;
import io.github.restioson.siege.game.SiegeTeams;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.fabricmc.fabric.api.util.NbtType;
import net.minecraft.block.BlockState;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.text.LiteralText;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.BiomeKeys;
import xyz.nucleoid.plasmid.game.GameOpenException;
import xyz.nucleoid.plasmid.game.player.GameTeam;
import xyz.nucleoid.plasmid.map.template.MapTemplate;
import xyz.nucleoid.plasmid.map.template.MapTemplateMetadata;
import xyz.nucleoid.plasmid.map.template.MapTemplateSerializer;
import xyz.nucleoid.plasmid.map.template.TemplateRegion;
import xyz.nucleoid.plasmid.util.BlockBounds;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class SiegeMapLoader {
    private final SiegeMapConfig config;

    public SiegeMapLoader(SiegeMapConfig config) {
        this.config = config;
    }

    public SiegeMap create() throws GameOpenException {
        try {
            MapTemplate template = MapTemplateSerializer.INSTANCE.loadFromResource(this.config.id);

            SiegeMap map = new SiegeMap(template, this.config.attackerSpawnAngle);
            template.setBiome(BiomeKeys.PLAINS);

            BlockBounds waitingSpawn = template.getMetadata().getFirstRegionBounds("waiting_spawn");
            if (waitingSpawn == null) {
                throw new GameOpenException(new LiteralText("waiting_spawn region required but not found"));
            }

            map.setWaitingSpawn(waitingSpawn);

            this.addFlagsToMap(map, template.getMetadata());
            map.kitStands.addAll(this.collectKitStands(map.flags, template));

            for (BlockPos pos : template.getBounds()) {
                BlockState state = template.getBlockState(pos);
                if (!state.isAir()) {
                    map.addProtectedBlock(pos.asLong());
                }
            }

            return map;
        } catch (IOException e) {
            throw new GameOpenException(new LiteralText("Failed to load map"), e);
        }
    }

    private List<SiegeKitStandLocation> collectKitStands(List<SiegeFlag> flags, MapTemplate template) {
        return template.getMetadata()
                .getRegions("kit_stand")
                .map(region -> {
                    CompoundTag data = region.getData();
                    GameTeam team = null;
                    if (data.contains("team")) {
                        team = this.parseTeam(data);
                    }

                    SiegeFlag flag = null;
                    if (data.contains("flag")) {
                        flag = flags.stream().filter(f -> f.id.equalsIgnoreCase(data.getString("flag"))).findAny().orElse(null);

                        if (flag == null) {
                            Siege.LOGGER.error("Unknown flag \"{}\"", data.getString("flag"));
                            throw new GameOpenException(new LiteralText("unknown flag"));
                        }
                    }

                    SiegeKit type = this.parseKitStandType(data);

                    return new SiegeKitStandLocation(team, flag, region.getBounds().getCenter(), type, data.getFloat("yaw"));
                })
                .collect(Collectors.toList());
    }

    private void addFlagsToMap(SiegeMap map, MapTemplateMetadata metadata) {
        Map<String, SiegeFlag> flags = new Object2ObjectOpenHashMap<>();

        metadata.getRegions("flag").forEach(region -> {
            BlockBounds bounds = region.getBounds();
            CompoundTag data = region.getData();
            String id = data.getString("id");
            String name = data.getString("name");
            GameTeam team = this.parseTeam(data);

            SiegeFlag flag = new SiegeFlag(id, name, team, bounds, null);
            if (data.contains("capturable") && !data.getBoolean("capturable")) {
                flag.capturable = false;
            }

            if (data.contains("plural") && data.getBoolean("plural")) {
                flag.pluralName = true;
            }

            flags.put(id, flag);
            map.flags.add(flag);
        });

        map.flags.sort(Comparator.comparing(flag -> flag.name));

        metadata.getRegions("flag").forEach(region -> {
            CompoundTag data = region.getData();
            String flagId = data.getString("id");

            SiegeFlag flag = flags.get(flagId);
            if (flag == null) {
                return;
            }

            ListTag prerequisiteFlagsList = data.getList("prerequisite_flags", NbtType.STRING);
            for (int i = 0; i < prerequisiteFlagsList.size(); i++) {
                String prerequisiteId = prerequisiteFlagsList.getString(i);

                SiegeFlag prerequisite = flags.get(prerequisiteId);
                if (prerequisite == null) {
                    Siege.LOGGER.error("Unknown flag \"{}\"", prerequisiteId);
                    throw new GameOpenException(new LiteralText("unknown flag"));
                }

                flag.prerequisiteFlags.add(prerequisite);
            }

            ListTag recapturePrerequisites = data.getList("recapture_prerequisites", NbtType.STRING);
            for (int i = 0; i < recapturePrerequisites.size(); i++) {
                String prerequisiteId = recapturePrerequisites.getString(i);

                SiegeFlag prerequisite = flags.get(prerequisiteId);
                if (prerequisite == null) {
                    Siege.LOGGER.error("Unknown flag \"{}\"", prerequisiteId);
                    throw new GameOpenException(new LiteralText("unknown flag"));
                }

                flag.recapturePrerequisites.add(prerequisite);
            }

            flag.flagIndicatorBlocks = metadata.getRegions("flag_indicator")
                    .filter(r -> flagId.equalsIgnoreCase(r.getData().getString("id")))
                    .map(TemplateRegion::getBounds)
                    .collect(Collectors.toList());
        });

        metadata.getRegions("respawn").forEach(region -> {
            CompoundTag data = region.getData();
            String flagId = data.getString("id");
            SiegeFlag flag = flags.get(flagId);
            if (flag != null) {
                flag.respawn = region.getBounds();

                if (data.contains("starting_spawn") && data.getBoolean("starting_spawn")) {
                    if (flag.team == SiegeTeams.DEFENDERS) {
                        map.defenderFirstSpawn = region.getBounds();
                    } else {
                        map.attackerFirstSpawn = region.getBounds();
                    }
                }
            } else {
                Siege.LOGGER.warn("Respawn attached to missing flag: {}", flagId);
            }
        });

        map.noBuildRegions = metadata.getRegionBounds("no_build").collect(Collectors.toList());

        map.gates = metadata.getRegions("gate_open")
                .map(region -> {
                    CompoundTag data = region.getData();

                    String id = data.getString("id");
                    SiegeFlag flag = flags.get(id);
                    if (flag == null) {
                        throw new GameOpenException(new LiteralText("Gate missing flag with id '" + id + "'!"));
                    }

                    TemplateRegion portcullisRegion = metadata.getRegions("portcullis")
                            .filter(r -> id.equalsIgnoreCase(r.getData().getString("id")))
                            .findFirst()
                            .orElseThrow(() -> {
                                Siege.LOGGER.error("Gate \"{}\" missing portcullis!", id);
                                return new GameOpenException(new LiteralText("Gate missing portcullis!"));
                            });

                    CompoundTag portcullisData = portcullisRegion.getData();
                    int retractHeight = portcullisData.getInt("retract_height");

                    int repairHealthThreshold = 25;

                    if (portcullisData.contains("repair_health_threshold")) {
                        repairHealthThreshold = portcullisData.getInt("repair_health_threshold");
                    }

                    int maxHealth = 50;

                    if (portcullisData.contains("max_health")) {
                        repairHealthThreshold = portcullisData.getInt("max_health");
                    }

                    BlockBounds brace = metadata.getRegions("gate_brace")
                            .filter(r -> id.equalsIgnoreCase(r.getData().getString("id")))
                            .map(TemplateRegion::getBounds)
                            .findFirst()
                            .orElse(null);

                    SiegeGate gate = new SiegeGate(flag, region.getBounds(), portcullisRegion.getBounds(), brace, retractHeight, repairHealthThreshold, maxHealth);
                    flag.gate = gate;
                    return gate;
                })
                .collect(Collectors.toList());

        for (SiegeFlag flag : flags.values()) {
            // TODO: remove this restriction (it's for warp enderpearl)
            if (flag.team == SiegeTeams.DEFENDERS && flag.respawn == null) {
                Siege.LOGGER.error("Flag \"{}\" missing respawn!", flag.name);
                throw new GameOpenException(new LiteralText("Flag missing respawn!"));
            }
        }
    }

    private GameTeam parseTeam(CompoundTag data) {
        String teamName = data.getString("team");
        GameTeam team = SiegeTeams.byKey(teamName);
        if (team == null) {
            Siege.LOGGER.error("Unknown team \"{}\"", teamName);
            return SiegeTeams.DEFENDERS;
        }
        return team;
    }

    private SiegeKit parseKitStandType(CompoundTag data) {
        String kitName = data.getString("type");
        SiegeKit type;
        switch (kitName) {
            case "bow":
                type = SiegeKit.ARCHER;
                break;
            case "sword":
                type = SiegeKit.SOLDIER;
                break;
            case "shield":
                type = SiegeKit.SHIELD_BEARER;
                break;
            case "builder":
                type = SiegeKit.CONSTRUCTOR;
                break;
            case "demolitioner":
                type = SiegeKit.DEMOLITIONER;
                break;
            default:
                Siege.LOGGER.error("Unknown kit \"" + kitName + "\"");
                throw new GameOpenException(new LiteralText("unknown kit"));
        }

        return type;
    }
}
