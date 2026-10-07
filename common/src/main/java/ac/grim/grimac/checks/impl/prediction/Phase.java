package ac.grim.grimac.checks.impl.prediction;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.manager.integrity.IntegritySignal;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.nmsutil.Collisions;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.defaulttags.BlockTags;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@CheckData(name = "Phase", stableKey = "grim.prediction.phase", description = "Moved into a solid block during movement prediction", setback = 1, decay = 0.005)
public class Phase extends Check implements PostPredictionListener {
    private SimpleCollisionBox oldBB;

    private boolean phaseProtectionEnabled;
    private boolean protectedImmediateSetback;
    private int recentBlockGraceTicks;
    private int sourceSearchPaddingBlocks;
    private double sourceMatchEpsilon;
    private Set<String> protectedMaterials = Collections.emptySet();
    private Set<ProtectedTag> protectedTags = Collections.emptySet();
    private Set<String> legacyExemptMaterials = Collections.emptySet();
    private Set<ProtectedTag> legacyExemptTags = Collections.emptySet();

    public Phase(GrimPlayer player) {
        super(player);
        oldBB = player.boundingBox;
    }

    @Override
    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        boolean deferOldBoundingBoxUpdate = false;

        if (!player.getSetbackTeleportUtil().blockOffsets
                && !predictionComplete.getData().isTeleport()
                && predictionComplete.isChecked()) {
            SimpleCollisionBox newBB = player.boundingBox;

            List<SimpleCollisionBox> boxes = new java.util.ArrayList<>();
            Collisions.getCollisionBoxes(player, newBB, boxes, false);

            for (SimpleCollisionBox box : boxes) {
                if (!newBB.isIntersected(box) || oldBB.isIntersected(box)) {
                    continue;
                }

                PhaseCollisionResolver.Source source = PhaseCollisionResolver.resolve(
                        player,
                        box,
                        sourceSearchPaddingBlocks,
                        sourceMatchEpsilon
                );

                WrappedBlockState state = source == null ? null : source.state();

                if (source != null
                        && recentBlockGraceTicks > 0
                        && player.blockHistory.hasRecentModification(
                        source.x(),
                        source.y(),
                        source.z(),
                        GrimAPI.INSTANCE.getTickManager().currentTick,
                        recentBlockGraceTicks)) {
                    deferOldBoundingBoxUpdate = true;
                    continue;
                }

                if (player.getClientVersion().isOlderThanOrEquals(ClientVersion.V_1_8)) {
                    WrappedBlockState legacyState = state;
                    if (legacyState == null) {
                        legacyState = player.compensatedWorld.getBlock(
                                (box.minX + box.maxX) * 0.5D,
                                (box.minY + box.maxY) * 0.5D,
                                (box.minZ + box.maxZ) * 0.5D
                        );
                    }

                    if (isLegacyExempt(legacyState)) {
                        continue;
                    }
                }

                String material = state == null ? "WORLD_BORDER_OR_UNKNOWN" : materialKey(state);
                boolean reinforced = state != null
                        && phaseProtectionEnabled
                        && isProtected(state);

                double correlation = GrimAPI.INSTANCE.getIntegrityCorrelationManager()
                        .record(player.uuid, IntegritySignal.PHASE);
                String environment = GrimAPI.INSTANCE.getEnvironmentContextManager().summary(player);

                StringBuilder verbose = new StringBuilder(112)
                        .append("corr=")
                        .append(String.format(Locale.ROOT, "%.2f", correlation))
                        .append(" env=")
                        .append(environment)
                        .append(" block=")
                        .append(material)
                        .append(" reinforced=")
                        .append(reinforced);

                if (source != null) {
                    verbose.append(" pos=")
                            .append(source.x()).append(',')
                            .append(source.y()).append(',')
                            .append(source.z());
                }

                if (reinforced && protectedImmediateSetback) {
                    if (flag(verbose.toString())) {
                        executeViolationSetback();
                    }
                } else {
                    flagWithSetback(verbose.toString());
                }
                return;
            }
        }

        if (!deferOldBoundingBoxUpdate) {
            oldBB = player.boundingBox;
        }
        reward();
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        super.onReload(config);

        phaseProtectionEnabled = requireBoolean(
                config,
                "world-integrity.phase-protection.enabled"
        );

        protectedImmediateSetback = requireBoolean(
                config,
                "world-integrity.phase-protection.immediate-setback"
        );

        recentBlockGraceTicks = requireNonNegativeInt(
                config,
                "world-integrity.phase-recent-block-grace-ticks"
        );

        sourceSearchPaddingBlocks = requireNonNegativeInt(
                config,
                "world-integrity.phase-protection.source-resolution.search-padding-blocks"
        );

        sourceMatchEpsilon = requirePositiveDouble(
                config,
                "world-integrity.phase-protection.source-resolution.match-epsilon"
        );

        protectedMaterials = parseMaterials(requireStringList(
                config,
                "world-integrity.phase-protection.protected-materials"
        ));

        protectedTags = parseTags(requireStringList(
                config,
                "world-integrity.phase-protection.protected-tags"
        ));

        legacyExemptMaterials = parseMaterials(requireStringList(
                config,
                "world-integrity.phase-protection.legacy-1_8-exemptions.materials"
        ));

        legacyExemptTags = parseTags(requireStringList(
                config,
                "world-integrity.phase-protection.legacy-1_8-exemptions.tags"
        ));
    }

    private boolean isProtected(WrappedBlockState state) {
        if (protectedMaterials.contains(materialKey(state))) {
            return true;
        }

        StateType type = state.getType();
        for (ProtectedTag tag : protectedTags) {
            if (tag.matches(type)) {
                return true;
            }
        }

        return false;
    }

    private boolean isLegacyExempt(WrappedBlockState state) {
        if (state == null) {
            return false;
        }

        if (legacyExemptMaterials.contains(materialKey(state))) {
            return true;
        }

        StateType type = state.getType();
        for (ProtectedTag tag : legacyExemptTags) {
            if (tag.matches(type)) {
                return true;
            }
        }

        return false;
    }

    private static Set<String> parseMaterials(List<String> values) {
        Set<String> parsed = new HashSet<>();
        for (String value : values) {
            String normalized = normalizeName(value);
            if (normalized.isEmpty()) {
                throw new IllegalStateException(
                        "world-integrity.phase-protection.protected-materials contains an empty value");
            }
            parsed.add(normalized);
        }
        return Collections.unmodifiableSet(parsed);
    }

    private static Set<ProtectedTag> parseTags(List<String> values) {
        Set<ProtectedTag> parsed = new HashSet<>();
        for (String value : values) {
            String normalized = normalizeName(value);
            try {
                parsed.add(ProtectedTag.valueOf(normalized));
            } catch (IllegalArgumentException ex) {
                throw new IllegalStateException(
                        "Unknown Phase protected tag '" + value
                                + "'. Supported tags: " + java.util.Arrays.toString(ProtectedTag.values()),
                        ex
                );
            }
        }
        return Collections.unmodifiableSet(parsed);
    }

    private static String materialKey(WrappedBlockState state) {
        if (state == null || state.getType() == null || state.getType().getName() == null) {
            return "UNKNOWN";
        }
        return normalizeName(state.getType().getName().getKey());
    }

    private static String normalizeName(String raw) {
        if (raw == null) {
            return "";
        }

        String value = raw.trim().toUpperCase(Locale.ROOT);
        int namespace = value.indexOf(':');
        if (namespace >= 0 && namespace + 1 < value.length()) {
            value = value.substring(namespace + 1);
        }

        return value.replace(' ', '_').replace('-', '_');
    }

    private static boolean requireBoolean(ConfigManager config, String key) {
        Object raw = config.get(key);
        if (!(raw instanceof Boolean value)) {
            throw invalidConfig(key, "boolean", raw);
        }
        return value;
    }

    private static int requireNonNegativeInt(ConfigManager config, String key) {
        Object raw = config.get(key);
        if (!(raw instanceof Number number)) {
            throw invalidConfig(key, "non-negative integer", raw);
        }

        int value = number.intValue();
        if (value < 0) {
            throw invalidConfig(key, "non-negative integer", raw);
        }
        return value;
    }

    private static double requirePositiveDouble(ConfigManager config, String key) {
        Object raw = config.get(key);
        if (!(raw instanceof Number number)) {
            throw invalidConfig(key, "positive number", raw);
        }

        double value = number.doubleValue();
        if (!Double.isFinite(value) || value <= 0.0D) {
            throw invalidConfig(key, "positive finite number", raw);
        }
        return value;
    }

    private static List<String> requireStringList(ConfigManager config, String key) {
        Object raw = config.get(key);
        if (!(raw instanceof List<?> rawList)) {
            throw invalidConfig(key, "string list", raw);
        }

        java.util.ArrayList<String> result = new java.util.ArrayList<>(rawList.size());
        for (Object element : rawList) {
            if (!(element instanceof String value)) {
                throw invalidConfig(key, "string list", raw);
            }
            result.add(value);
        }
        return result;
    }

    private static IllegalStateException invalidConfig(String key, String expected, Object actual) {
        String actualType = actual == null ? "missing" : actual.getClass().getSimpleName();
        return new IllegalStateException(
                "Invalid SparkGrim config key '" + key + "': expected "
                        + expected + ", got " + actualType + ". Run/update to config-version 15."
        );
    }

    private enum ProtectedTag {
        DOORS {
            @Override
            boolean matches(StateType type) {
                return BlockTags.DOORS.contains(type);
            }
        },
        TRAPDOORS {
            @Override
            boolean matches(StateType type) {
                return BlockTags.TRAPDOORS.contains(type);
            }
        },
        FENCE_GATES {
            @Override
            boolean matches(StateType type) {
                return BlockTags.FENCE_GATES.contains(type);
            }
        },
        FENCES {
            @Override
            boolean matches(StateType type) {
                return BlockTags.FENCES.contains(type);
            }
        },
        WALLS {
            @Override
            boolean matches(StateType type) {
                return BlockTags.WALLS.contains(type);
            }
        },
        ANVILS {
            @Override
            boolean matches(StateType type) {
                return BlockTags.ANVIL.contains(type);
            }
        };

        abstract boolean matches(StateType type);
    }
}
