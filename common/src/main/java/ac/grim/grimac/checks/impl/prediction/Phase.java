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
import ac.grim.grimac.utils.anticheat.LogUtil;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.defaulttags.BlockTags;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@CheckData(name = "Phase", stableKey = "grim.prediction.phase", description = "Moved into a solid block during movement prediction", setback = 1, decay = 0.005)
public class Phase extends Check implements PostPredictionListener {
    private static final AtomicBoolean CONFIG_ERROR_LOGGED = new AtomicBoolean();

    private SimpleCollisionBox oldBB;
    private boolean configurationValid;

    private boolean phaseProtectionEnabled;
    private boolean protectedImmediateSetback;
    private boolean legacyExemptionsEnabled;
    private int recentBlockGraceTicks;
    private int sourceSearchPaddingBlocks;
    private double sourceMatchEpsilon;

    private Set<String> protectedMaterials = Collections.emptySet();
    private Set<StateType> protectedTagStates = Collections.emptySet();
    private Set<String> legacyExemptMaterials = Collections.emptySet();
    private Set<StateType> legacyExemptTagStates = Collections.emptySet();

    private final Map<BlockKey, Integer> pendingGrace = new HashMap<>();

    public Phase(GrimPlayer player) {
        super(player);
        oldBB = player.boundingBox;
    }

    @Override
    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        if (!configurationValid) {
            pendingGrace.clear();
            oldBB = player.boundingBox;
            reward();
            return;
        }

        if (player.getSetbackTeleportUtil().blockOffsets
                || predictionComplete.getData().isTeleport()
                || !predictionComplete.isChecked()) {
            pendingGrace.clear();
            oldBB = player.boundingBox;
            reward();
            return;
        }

        final SimpleCollisionBox newBB = player.boundingBox;
        final int currentTick = GrimAPI.INSTANCE.getTickManager().currentTick;

        if (processPendingGrace(newBB, currentTick)) {
            return;
        }

        List<SimpleCollisionBox> boxes = new ArrayList<>();
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

            if (source != null
                    && recentBlockGraceTicks > 0
                    && player.blockHistory.hasRecentModification(
                    source.x(),
                    source.y(),
                    source.z(),
                    currentTick,
                    recentBlockGraceTicks)) {
                pendingGrace.putIfAbsent(
                        new BlockKey(source.x(), source.y(), source.z()),
                        currentTick
                );
                continue;
            }

            if (handleCollision(box, source)) {
                return;
            }
        }

        oldBB = player.boundingBox;
        reward();
    }

    private boolean processPendingGrace(SimpleCollisionBox playerBox, int currentTick) {
        if (pendingGrace.isEmpty()) {
            return false;
        }

        Iterator<Map.Entry<BlockKey, Integer>> iterator = pendingGrace.entrySet().iterator();

        while (iterator.hasNext()) {
            Map.Entry<BlockKey, Integer> entry = iterator.next();
            BlockKey key = entry.getKey();

            PhaseCollisionResolver.Source source = PhaseCollisionResolver.resolveAt(
                    player,
                    playerBox,
                    key.x(),
                    key.y(),
                    key.z()
            );

            if (source == null) {
                iterator.remove();
                continue;
            }

            int elapsedTicks = currentTick - entry.getValue();
            if (elapsedTicks <= recentBlockGraceTicks) {
                continue;
            }

            iterator.remove();

            if (handleCollision(source.box(), source)) {
                return true;
            }
        }

        return false;
    }

    private boolean handleCollision(
            SimpleCollisionBox box,
            PhaseCollisionResolver.Source source
    ) {
        WrappedBlockState state = source == null ? null : source.state();

        if (legacyExemptionsEnabled
                && player.getClientVersion().isOlderThanOrEquals(ClientVersion.V_1_8)) {
            WrappedBlockState legacyState = state;

            if (legacyState == null) {
                legacyState = player.compensatedWorld.getBlock(
                        (box.minX + box.maxX) * 0.5D,
                        (box.minY + box.maxY) * 0.5D,
                        (box.minZ + box.maxZ) * 0.5D
                );
            }

            if (isLegacyExempt(legacyState)) {
                return false;
            }
        }

        String material = state == null ? "WORLD_BORDER_OR_UNKNOWN" : materialKey(state.getType());
        boolean reinforced = state != null
                && phaseProtectionEnabled
                && isProtected(state.getType());

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

        return true;
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        super.onReload(config);

        configurationValid = false;
        pendingGrace.clear();

        try {

            phaseProtectionEnabled = requireBoolean(
                    config,
                    "world-integrity.phase-protection.enabled"
            );

            protectedImmediateSetback = requireBoolean(
                    config,
                    "world-integrity.phase-protection.immediate-setback"
            );

            legacyExemptionsEnabled = requireBoolean(
                    config,
                    "world-integrity.phase-protection.legacy-1_8-exemptions.enabled"
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

            protectedMaterials = resolveMaterials(
                    requireStringList(
                            config,
                            "world-integrity.phase-protection.protected-materials"
                    ),
                    "world-integrity.phase-protection.protected-materials"
            );

            protectedTagStates = resolveTagStates(
                    requireStringList(
                            config,
                            "world-integrity.phase-protection.protected-tags"
                    ),
                    "world-integrity.phase-protection.protected-tags"
            );

            legacyExemptMaterials = resolveMaterials(
                    requireStringList(
                            config,
                            "world-integrity.phase-protection.legacy-1_8-exemptions.materials"
                    ),
                    "world-integrity.phase-protection.legacy-1_8-exemptions.materials"
            );

            legacyExemptTagStates = resolveTagStates(
                    requireStringList(
                            config,
                            "world-integrity.phase-protection.legacy-1_8-exemptions.tags"
                    ),
                    "world-integrity.phase-protection.legacy-1_8-exemptions.tags"
            );


            configurationValid = true;
            CONFIG_ERROR_LOGGED.set(false);
        } catch (RuntimeException ex) {
            protectedMaterials = Collections.emptySet();
            protectedTagStates = Collections.emptySet();
            legacyExemptMaterials = Collections.emptySet();
            legacyExemptTagStates = Collections.emptySet();

            if (CONFIG_ERROR_LOGGED.compareAndSet(false, true)) {
                LogUtil.error(
                        "Phase 4B.1 configuration is invalid. Phase is disabled until the config is fixed and Grim is reloaded. "
                                + "This safety fallback prevents one missing key from breaking GrimPlayer creation.",
                        ex
                );
            }
        }
    }

    private boolean isProtected(StateType type) {
        return protectedMaterials.contains(materialKey(type))
                || protectedTagStates.contains(type);
    }

    private boolean isLegacyExempt(WrappedBlockState state) {
        if (state == null || state.getType() == null) {
            return false;
        }

        StateType type = state.getType();
        return legacyExemptMaterials.contains(materialKey(type))
                || legacyExemptTagStates.contains(type);
    }

    private static Set<String> resolveMaterials(List<String> values, String configKey) {
        Set<String> parsed = new HashSet<>();

        for (String raw : values) {
            String fieldName = normalizeName(raw);
            if (fieldName.isEmpty()) {
                throw invalidConfig(configKey, "non-empty StateTypes names", raw);
            }

            try {
                Field field = StateTypes.class.getField(fieldName);

                if (!Modifier.isStatic(field.getModifiers())) {
                    throw invalidConfig(configKey, "static StateTypes names", raw);
                }

                Object value = field.get(null);
                if (!(value instanceof StateType stateType)) {
                    throw invalidConfig(configKey, "StateTypes block names", raw);
                }

                parsed.add(materialKey(stateType));
            } catch (NoSuchFieldException ex) {
                throw new IllegalStateException(
                        "Unknown block '" + raw + "' in '" + configKey
                                + "'. Use the StateTypes field name used by the installed PacketEvents version.",
                        ex
                );
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException(
                        "Could not resolve block '" + raw + "' from '" + configKey + "'.",
                        ex
                );
            }
        }

        return Collections.unmodifiableSet(parsed);
    }

    private static Set<StateType> resolveTagStates(List<String> values, String configKey) {
        Set<StateType> parsed = new HashSet<>();

        for (String raw : values) {
            String fieldName = normalizeName(raw);
            if (fieldName.isEmpty()) {
                throw invalidConfig(configKey, "non-empty BlockTags names", raw);
            }

            try {
                Field field = BlockTags.class.getField(fieldName);

                if (!Modifier.isStatic(field.getModifiers())) {
                    throw invalidConfig(configKey, "static BlockTags names", raw);
                }

                Object tag = field.get(null);
                if (tag == null) {
                    throw invalidConfig(configKey, "valid BlockTags names", raw);
                }

                Method getStates = tag.getClass().getMethod("getStates");
                Object rawStates = getStates.invoke(tag);

                if (!(rawStates instanceof Iterable<?> states)) {
                    throw invalidConfig(configKey, "BlockTags with iterable states", raw);
                }

                for (Object state : states) {
                    if (!(state instanceof StateType stateType)) {
                        throw invalidConfig(configKey, "BlockTags containing StateType values", raw);
                    }
                    parsed.add(stateType);
                }
            } catch (NoSuchFieldException ex) {
                throw new IllegalStateException(
                        "Unknown block tag '" + raw + "' in '" + configKey
                                + "'. Use a public BlockTags field from the installed PacketEvents version.",
                        ex
                );
            } catch (ReflectiveOperationException ex) {
                throw new IllegalStateException(
                        "Could not resolve block tag '" + raw + "' from '" + configKey + "'.",
                        ex
                );
            }
        }

        return Collections.unmodifiableSet(parsed);
    }

    private static String materialKey(StateType type) {
        if (type == null || type.getName() == null) {
            return "UNKNOWN";
        }

        return normalizeName(type.getName());
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

        List<String> result = new ArrayList<>(rawList.size());

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
                        + expected + ", got " + actualType
                        + ". Update the bundled config to config-version 15."
        );
    }

    private record BlockKey(int x, int y, int z) {
    }
}
