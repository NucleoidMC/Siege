package io.github.restioson.siege.game.map;

import com.google.common.base.Strings;
import io.github.restioson.siege.Siege;
import io.github.restioson.siege.game.SiegeKit;
import io.github.restioson.siege.game.SiegeTeams;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.block.*;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.BiomeKeys;
import org.jetbrains.annotations.Nullable;
import xyz.nucleoid.map_templates.*;
import xyz.nucleoid.plasmid.api.game.GameOpenException;
import xyz.nucleoid.plasmid.api.game.common.team.GameTeam;

import java.io.IOException;
import java.net.URL;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class SiegeMapLoader {
    public static boolean loadRemote() {
        return System.getenv().getOrDefault("SIEGE_LOAD_MAPS_FROM_BUILD", "false").equals("true");
    }

    public static SiegeMap load(MinecraftServer server, SiegeMapConfig config) throws GameOpenException {
        MapTemplate template;
        try {
            if (loadRemote()) {
                Siege.LOGGER.info("Loading map from build server");

                var id = config.templateId();
                var uri = String.format(
                        "https://build.nucleoid.xyz/nucleoid_creator_tools/export/%s/map_templates/%s.nbt",
                        id.getNamespace(),
                        id.getPath()
                );
                template = MapTemplateSerializer.loadFrom(new URL(uri).openStream(), server.getRegistryManager());
            } else {
                Siege.LOGGER.info("Loading map from resources");
                template = MapTemplateSerializer.loadFromResource(server, config.templateId());
            }
        } catch (IOException e) {
            throw new GameOpenException(Text.literal(String.format("Failed to load map template %s", config.templateId())), e);
        }

        MapTemplateMetadata metadata = template.getMetadata();

        SiegeMap map = new SiegeMap(template);

        NbtCompound mapData = metadata.getData();
        String biomeId = mapData.getString("biome", "");
        if (!Strings.isNullOrEmpty(biomeId)) {
            template.setBiome(RegistryKey.of(RegistryKeys.BIOME, Identifier.of(biomeId)));
        } else {
            template.setBiome(BiomeKeys.PLAINS);
        }

        if (mapData.contains("time")) {
            map.time = mapData.getLong("time", 0);
        }

        TemplateRegion waitingSpawn = metadata.getFirstRegion("waiting_spawn");
        if (waitingSpawn == null) {
            throw new GameOpenException(Text.literal("waiting_spawn region required but not found"));
        }

        map.setWaitingSpawn(new SiegeSpawn(waitingSpawn.getBounds(), waitingSpawn.getData().getFloat("yaw", 0)));

        addFlagsToMap(map, metadata);
        map.kitStands.addAll(collectKitStands(map.flags, template));

        for (BlockPos pos : template.getBounds()) {
            BlockState state = template.getBlockState(pos);
            Block block = state.getBlock();

            boolean destructible = block instanceof PaneBlock || block instanceof VineBlock || block instanceof PlantBlock || block instanceof FluidBlock;
            if (!state.isAir() && !destructible) {
                map.addProtectedBlock(pos.asLong());
            }
        }

        return map;
    }

    private static List<SiegeKitStandData> collectKitStands(List<SiegeFlag> flags, MapTemplate template) {
        return template.getMetadata()
                .getRegions("kit_stand")
                .map(region -> {
                    NbtCompound data = region.getData();
                    GameTeam team = null;
                    if (data.contains("team")) {
                        team = parseTeam(data);
                    }

                    SiegeFlag flag = null;
                    if (data.contains("flag")) {
                        flag = flags.stream().filter(f -> f.id.equalsIgnoreCase(data.getString("flag", ""))).findAny().orElse(null);

                        if (flag == null) {
                            Siege.LOGGER.error("Unknown flag \"{}\"", data.getString("flag"));
                            throw new GameOpenException(Text.literal("unknown flag"));
                        }
                    }

                    SiegeKit type = parseKitStandType(data);

                    return new SiegeKitStandData(
                            team,
                            flag,
                            region.getBounds().centerBottom(),
                            type,
                            data.getFloat("yaw", 0)
                    );
                })
                .collect(Collectors.toList());
    }

    private static void addFlagsToMap(SiegeMap map, MapTemplateMetadata metadata) {
        Map<String, SiegeFlag> flags = new Object2ObjectOpenHashMap<>();

        metadata.getRegions("flag").forEach(region -> {
            BlockBounds bounds = region.getBounds();
            NbtCompound data = region.getData();
            String id = data.getString("id", "");
            String name = data.getString("name", "");
            GameTeam team = parseTeam(data);

            SiegeFlag flag = new SiegeFlag(id, name, team, bounds);
            if (data.contains("capturable") && !data.getBoolean("capturable", false)) {
                flag.capturable = false;
            }

            if (data.contains("plural") && data.getBoolean("plural", false)) {
                flag.pluralName = true;
            }

            if (data.contains("icon")) {
                String icon = data.getString("icon", "");
                flag.icon = new ItemStack(Registries.ITEM.get(Identifier.of(icon)));
            }

            flags.put(id, flag);
            map.flags.add(flag);
        });

        map.flags.sort(Comparator.comparing(flag -> flag.name));

        metadata.getRegions("flag").forEach(region -> {
            NbtCompound data = region.getData();
            String flagId = data.getString("id", "");

            SiegeFlag flag = flags.get(flagId);
            if (flag == null) {
                return;
            }

            NbtList prerequisiteFlagsList = data.getListOrEmpty("prerequisite_flags");
            for (int i = 0; i < prerequisiteFlagsList.size(); i++) {
                String prerequisiteId = prerequisiteFlagsList.getString(i, "");

                SiegeFlag prerequisite = flags.get(prerequisiteId);
                if (prerequisite == null) {
                    Siege.LOGGER.error("Unknown flag \"{}\"", prerequisiteId);
                    throw new GameOpenException(Text.literal("unknown flag"));
                }

                flag.prerequisiteFlags.add(prerequisite);
            }

            NbtList recapturePrerequisites = data.getListOrEmpty("recapture_prerequisites");
            for (int i = 0; i < recapturePrerequisites.size(); i++) {
                String prerequisiteId = recapturePrerequisites.getString(i, "");

                SiegeFlag prerequisite = flags.get(prerequisiteId);
                if (prerequisite == null) {
                    Siege.LOGGER.error("Unknown flag \"{}\"", prerequisiteId);
                    throw new GameOpenException(Text.literal("unknown flag"));
                }

                flag.recapturePrerequisites.add(prerequisite);
            }

            flag.flagIndicatorBlocks = metadata.getRegions("flag_indicator")
                    .filter(r -> flagId.equalsIgnoreCase(r.getData().getString("id", "")))
                    .map(TemplateRegion::getBounds)
                    .collect(Collectors.toList());
        });

        metadata.getRegions("respawn").forEach(region -> {
            NbtCompound data = region.getData();
            String flagId = data.getString("id", "");
            SiegeFlag flag = flags.get(flagId);
            if (flag != null) {
                float yaw = data.getFloat("yaw", 0);
                SiegeSpawn respawn = new SiegeSpawn(region.getBounds(), yaw);

                GameTeam team = parseOptionalTeam(data);
                if (team == SiegeTeams.DEFENDERS) {
                    flag.defenderRespawn = respawn;
                } else if (team == SiegeTeams.ATTACKERS) {
                    flag.attackerRespawn = respawn;
                } else {
                    flag.defenderRespawn = flag.attackerRespawn = respawn;
                }

                if (data.contains("starting_spawn") && data.getBoolean("starting_spawn", false)) {
                    if (flag.team == SiegeTeams.DEFENDERS) {
                        map.defenderFirstSpawn = respawn;
                    } else {
                        map.attackerFirstSpawn = respawn;
                    }
                }
            } else {
                Siege.LOGGER.warn("Skipping respawn at {} as flag '{}' is missing", region.getBounds().center(), flagId);
            }
        });

        for (var flag : map.flags) {
            if (flag.attackerRespawn == null) {
                throw new GameOpenException(Text.literal("Flag %s missing respawn for attackers!".formatted(flag.name)));
            }

            if (flag.defenderRespawn == null) {
                throw new GameOpenException(Text.literal("Flag %s missing respawn for defenders!".formatted(flag.name)));
            }
        }

        map.noBuildRegions = metadata.getRegionBounds("no_build").collect(Collectors.toList());

        map.gates = metadata.getRegions("gate_open")
                .map(region -> {
                    NbtCompound data = region.getData();

                    String gateId = data.getString("id", "");
                    String flagIdRaw = data.getString("flag", "");
                    final String flagId = flagIdRaw.isEmpty() ? gateId : flagIdRaw;

                    SiegeFlag flag = flags.get(flagId);
                    if (flag == null) {
                        var text = Text.literal(String.format("Gate (id '%s') missing flag with id '%s'!", gateId, flagId));

                        if (flagIdRaw.isEmpty()) {
                            text = text.append(Text.literal("\nNote: flag id was implicitly defined as the gate id, as `flag` was missing in data."));
                        }

                        throw new GameOpenException(text);
                    }

                    TemplateRegion portcullisRegion = metadata.getRegions("portcullis")
                            .filter(r -> gateId.equalsIgnoreCase(r.getData().getString("id", "")))
                            .findFirst()
                            .orElseThrow(() -> {
                                Siege.LOGGER.error("Gate \"{}\" missing portcullis!", gateId);
                                return new GameOpenException(Text.literal(String.format("Gate (id '%s') missing portcullis!", gateId)));
                            });

                    NbtCompound portcullisData = portcullisRegion.getData();
                    int retractHeight = portcullisData.getInt("retract_height", 0);

                    int repairHealthThreshold = 50;

                    if (portcullisData.contains("repair_health_threshold")) {
                        repairHealthThreshold = portcullisData.getInt("repair_health_threshold",0);
                    }

                    int maxHealth = 100;

                    if (portcullisData.contains("max_health")) {
                        repairHealthThreshold = portcullisData.getInt("max_health", 0);
                    }

                    BlockBounds brace = metadata.getRegions("gate_brace")
                            .filter(r -> gateId.equalsIgnoreCase(r.getData().getString("id", "")))
                            .map(TemplateRegion::getBounds)
                            .findFirst()
                            .orElse(null);

                    var name = data.getString("name", "");
                    var plural = data.getBoolean("plural", false);

                    if (name.isEmpty()) {
                        name = flag.name;
                        plural = flag.pluralName;
                    }

                    SiegeGate gate = new SiegeGate(gateId, flag, region.getBounds(), portcullisRegion.getBounds(), brace, retractHeight, repairHealthThreshold, maxHealth, name, plural);
                    flag.gates.add(gate);
                    return gate;
                })
                .collect(Collectors.toList());

        for (SiegeFlag flag : flags.values()) {
            // TODO: remove this restriction (it's for warp enderpearl)
            if (flag.team == SiegeTeams.DEFENDERS && flag.defenderRespawn == null) {
                Siege.LOGGER.error("Flag \"{}\" missing respawn!", flag.name);
                throw new GameOpenException(Text.literal("Flag missing respawn!"));
            }
        }
    }

    private static GameTeam parseTeam(NbtCompound data) {
        String teamName = data.getString("team", "").toLowerCase();
        GameTeam team = SiegeTeams.byKey(teamName);
        if (team == null) {
            Siege.LOGGER.error("Unknown team \"{}\"", teamName);
            return SiegeTeams.DEFENDERS;
        }
        return team;
    }

    @Nullable
    private static GameTeam parseOptionalTeam(NbtCompound data) {
        String teamName = data.getString("team", "");
        return SiegeTeams.byKey(teamName);
    }

    private static SiegeKit parseKitStandType(NbtCompound data) {
        String kitName = data.getString("type", "");
        return switch (kitName) {
            case "bow" -> SiegeKit.ARCHER;
            case "sword" -> SiegeKit.SOLDIER;
            case "shield" -> SiegeKit.SHIELD_BEARER;
            case "builder" -> SiegeKit.ENGINEER;
            case "captain" -> SiegeKit.CAPTAIN;
            case "demolitioner" -> SiegeKit.ENGINEER; // TODO HACK: remove later
            default -> {
                Siege.LOGGER.error("Unknown kit \"" + kitName + "\"");
                throw new GameOpenException(Text.literal("unknown kit"));
            }
        };
    }
}
