package ac.grim.grimac.manager.integrity;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.math.GrimMath;
import ac.grim.grimac.utils.nmsutil.StuckSpeed;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.defaulttags.BlockTags;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import org.jetbrains.annotations.NotNull;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

public final class EnvironmentContextManager {
    private volatile boolean enabled = true;
    private volatile int recentBlockTicks = 4;

    private volatile double cobwebSimulationMultiplier = 0.35D;
    private volatile double stuckMovementSimulationMultiplier = 0.50D;
    private volatile double climbableSimulationMultiplier = 0.55D;
    private volatile double liquidSimulationMultiplier = 0.60D;
    private volatile double complexCollisionSimulationMultiplier = 0.70D;
    private volatile double pistonSimulationMultiplier = 0.55D;
    private volatile double recentBlockSimulationMultiplier = 0.55D;

    private volatile double cobwebEnforcementMultiplier = 0.15D;
    private volatile double stuckMovementEnforcementMultiplier = 0.35D;
    private volatile double climbableEnforcementMultiplier = 0.45D;
    private volatile double liquidEnforcementMultiplier = 0.55D;
    private volatile double complexCollisionEnforcementMultiplier = 0.65D;
    private volatile double pistonEnforcementMultiplier = 0.45D;
    private volatile double recentBlockEnforcementMultiplier = 0.50D;

    public void reload(@NotNull ConfigManager config) {
        enabled = config.getBooleanElse("environment-context.enabled", true);
        recentBlockTicks = (int) clamp(
                config.getLongElse("environment-context.recent-block-change-ticks", 4L),
                0L,
                20L
        );

        cobwebSimulationMultiplier = clamp(
                config.getDoubleElse("environment-context.simulation-multipliers.cobweb", 0.35D),
                0.0D,
                1.0D
        );
        stuckMovementSimulationMultiplier = clamp(
                config.getDoubleElse("environment-context.simulation-multipliers.stuck-movement", 0.50D),
                0.0D,
                1.0D
        );
        climbableSimulationMultiplier = clamp(
                config.getDoubleElse("environment-context.simulation-multipliers.climbable", 0.55D),
                0.0D,
                1.0D
        );
        liquidSimulationMultiplier = clamp(
                config.getDoubleElse("environment-context.simulation-multipliers.liquid", 0.60D),
                0.0D,
                1.0D
        );
        complexCollisionSimulationMultiplier = clamp(
                config.getDoubleElse("environment-context.simulation-multipliers.complex-collision", 0.70D),
                0.0D,
                1.0D
        );
        pistonSimulationMultiplier = clamp(
                config.getDoubleElse("environment-context.simulation-multipliers.piston", 0.55D),
                0.0D,
                1.0D
        );
        recentBlockSimulationMultiplier = clamp(
                config.getDoubleElse("environment-context.simulation-multipliers.recent-block-change", 0.55D),
                0.0D,
                1.0D
        );

        cobwebEnforcementMultiplier = clamp(
                config.getDoubleElse("environment-context.enforcement-multipliers.cobweb", 0.15D),
                0.0D,
                1.0D
        );
        stuckMovementEnforcementMultiplier = clamp(
                config.getDoubleElse("environment-context.enforcement-multipliers.stuck-movement", 0.35D),
                0.0D,
                1.0D
        );
        climbableEnforcementMultiplier = clamp(
                config.getDoubleElse("environment-context.enforcement-multipliers.climbable", 0.45D),
                0.0D,
                1.0D
        );
        liquidEnforcementMultiplier = clamp(
                config.getDoubleElse("environment-context.enforcement-multipliers.liquid", 0.55D),
                0.0D,
                1.0D
        );
        complexCollisionEnforcementMultiplier = clamp(
                config.getDoubleElse("environment-context.enforcement-multipliers.complex-collision", 0.65D),
                0.0D,
                1.0D
        );
        pistonEnforcementMultiplier = clamp(
                config.getDoubleElse("environment-context.enforcement-multipliers.piston", 0.45D),
                0.0D,
                1.0D
        );
        recentBlockEnforcementMultiplier = clamp(
                config.getDoubleElse("environment-context.enforcement-multipliers.recent-block-change", 0.50D),
                0.0D,
                1.0D
        );
    }

    public @NotNull Set<EnvironmentContext> classify(@NotNull GrimPlayer player) {
        if (!enabled) {
            return Set.of(EnvironmentContext.NORMAL);
        }

        EnumSet<EnvironmentContext> contexts =
                EnumSet.noneOf(EnvironmentContext.class);

        int stuckMask =
                player.stuckSpeedMultiplier.getIndex()
                        | player.lastStuckSpeedMultiplier.getIndex();

        int webMask =
                StuckSpeed.COBWEB.getIndex()
                        | StuckSpeed.COBWEB_WEAVING.getIndex();

        if ((stuckMask & webMask) != 0) {
            contexts.add(EnvironmentContext.COBWEB);
        }

        if ((stuckMask & ~webMask) != 0) {
            contexts.add(EnvironmentContext.STUCK_MOVEMENT);
        }

        if (player.isClimbing || player.lastWasClimbing > 0.0D) {
            contexts.add(EnvironmentContext.CLIMBABLE);
        }

        if (player.wasTouchingWater || player.wasWasTouchingWater) {
            contexts.add(EnvironmentContext.WATER);
        }

        if (player.wasTouchingLava) {
            contexts.add(EnvironmentContext.LAVA);
        }

        int x = GrimMath.floor(player.x);
        int y = GrimMath.floor(player.y);
        int z = GrimMath.floor(player.z);

        int minX = GrimMath.floor(player.boundingBox.minX - 0.03D);
        int minY = GrimMath.floor(player.boundingBox.minY - 0.03D);
        int minZ = GrimMath.floor(player.boundingBox.minZ - 0.03D);

        int maxX = GrimMath.floor(player.boundingBox.maxX + 0.03D);
        int maxY = GrimMath.floor(player.boundingBox.maxY + 0.03D);
        int maxZ = GrimMath.floor(player.boundingBox.maxZ + 0.03D);

        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    inspectBlock(player, bx, by, bz, contexts);
                }
            }
        }

        inspectBlock(player, x, y - 1, z, contexts);

        if (recentBlockTicks > 0 && hasRecentWorldChange(player, x, y, z)) {
            contexts.add(EnvironmentContext.RECENT_BLOCK_CHANGE);
        }

        if (contexts.isEmpty()) {
            contexts.add(EnvironmentContext.NORMAL);
        }

        return Set.copyOf(contexts);
    }

    public double simulationCorrelationMultiplier(@NotNull GrimPlayer player) {
        double multiplier = 1.0D;

        for (EnvironmentContext context : classify(player)) {
            multiplier = Math.min(
                    multiplier,
                    simulationMultiplier(context)
            );
        }

        return multiplier;
    }

    public double enforcementMultiplier(@NotNull GrimPlayer player) {
        double multiplier = 1.0D;

        for (EnvironmentContext context : classify(player)) {
            multiplier = Math.min(
                    multiplier,
                    enforcementMultiplier(context)
            );
        }

        return multiplier;
    }

    public boolean isSpecialMovementEnvironment(@NotNull GrimPlayer player) {
        Set<EnvironmentContext> contexts = classify(player);
        return !(contexts.size() == 1
                && contexts.contains(EnvironmentContext.NORMAL));
    }

    public @NotNull String summary(@NotNull GrimPlayer player) {
        Set<EnvironmentContext> contexts = classify(player);

        if (contexts.size() == 1
                && contexts.contains(EnvironmentContext.NORMAL)) {
            return "NORMAL";
        }

        return contexts.stream()
                .filter(context -> context != EnvironmentContext.NORMAL)
                .map(context -> context.name().toLowerCase(Locale.ROOT))
                .sorted()
                .reduce((left, right) -> left + "," + right)
                .orElse("normal");
    }

    private double simulationMultiplier(EnvironmentContext context) {
        return switch (context) {
            case COBWEB -> cobwebSimulationMultiplier;
            case STUCK_MOVEMENT -> stuckMovementSimulationMultiplier;
            case CLIMBABLE -> climbableSimulationMultiplier;
            case WATER, LAVA -> liquidSimulationMultiplier;
            case COMPLEX_COLLISION -> complexCollisionSimulationMultiplier;
            case PISTON -> pistonSimulationMultiplier;
            case RECENT_BLOCK_CHANGE -> recentBlockSimulationMultiplier;
            case NORMAL -> 1.0D;
        };
    }

    private double enforcementMultiplier(EnvironmentContext context) {
        return switch (context) {
            case COBWEB -> cobwebEnforcementMultiplier;
            case STUCK_MOVEMENT -> stuckMovementEnforcementMultiplier;
            case CLIMBABLE -> climbableEnforcementMultiplier;
            case WATER, LAVA -> liquidEnforcementMultiplier;
            case COMPLEX_COLLISION -> complexCollisionEnforcementMultiplier;
            case PISTON -> pistonEnforcementMultiplier;
            case RECENT_BLOCK_CHANGE -> recentBlockEnforcementMultiplier;
            case NORMAL -> 1.0D;
        };
    }

    private void inspectBlock(
            GrimPlayer player,
            int x,
            int y,
            int z,
            EnumSet<EnvironmentContext> contexts
    ) {
        WrappedBlockState state =
                player.compensatedWorld.getBlock(x, y, z);

        StateType type = state.getType();

        if (type == StateTypes.COBWEB) {
            contexts.add(EnvironmentContext.COBWEB);
        }

        if (type == StateTypes.PISTON
                || type == StateTypes.STICKY_PISTON
                || type == StateTypes.PISTON_HEAD
                || type == StateTypes.MOVING_PISTON) {
            contexts.add(EnvironmentContext.PISTON);
        }

        if (BlockTags.STAIRS.contains(type)
                || BlockTags.SLABS.contains(type)
                || BlockTags.FENCES.contains(type)
                || BlockTags.WALLS.contains(type)
                || BlockTags.FENCE_GATES.contains(type)
                || BlockTags.DOORS.contains(type)
                || BlockTags.TRAPDOORS.contains(type)
                || type == StateTypes.SNOW) {
            contexts.add(EnvironmentContext.COMPLEX_COLLISION);
        }
    }

    private boolean hasRecentWorldChange(
            GrimPlayer player,
            int x,
            int y,
            int z
    ) {
        int tick = GrimAPI.INSTANCE.getTickManager().currentTick;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (player.blockHistory.hasRecentModification(
                            x + dx,
                            y + dy,
                            z + dz,
                            tick,
                            recentBlockTicks
                    )) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(
            double value,
            double min,
            double max
    ) {
        return Math.max(min, Math.min(max, value));
    }
}
