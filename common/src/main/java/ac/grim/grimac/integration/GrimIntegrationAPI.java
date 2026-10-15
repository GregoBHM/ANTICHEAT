package ac.grim.grimac.integration;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.manager.integrity.IntegritySnapshot;
import ac.grim.grimac.manager.integrity.InteractionContextManager;
import ac.grim.grimac.manager.integrity.InteractionContextType;
import ac.grim.grimac.manager.integrity.MovementContextManager;
import ac.grim.grimac.manager.integrity.MovementContextType;
import ac.grim.grimac.utils.math.Vector3dm;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Small public facade intended for trusted server plugins such as MagicSpells, RPGItems or CombatPlus.
 *
 * <p>It does not disable Grim checks. It only registers short-lived, source-aware movement contexts.
 * Plugins should call this API immediately before/when they intentionally create exceptional movement.</p>
 */
public final class GrimIntegrationAPI {
    private GrimIntegrationAPI() {
    }

    /**
     * Backward-compatible generic movement context.
     *
     * <p>Velocity-capable types without a concrete vector are OBSERVED only.
     * This preserves older RPGItems/MagicSpells/CombatPlus integrations while
     * preventing an unscoped legacy call from creating Trusted velocity.
     * Non-velocity state such as TELEPORT/FREEZE/ROOT/FLIGHT remains trusted
     * because the type itself fully scopes the exceptional state.</p>
     */
    public static MovementContextManager.Context beginMovementContext(@NotNull UUID playerId,
                                                                      @NotNull MovementContextType type,
                                                                      @NotNull String sourcePlugin,
                                                                      long durationMillis) {
        return beginMovementContext(
                playerId, type, sourcePlugin, durationMillis, null
        );
    }

    public static MovementContextManager.Context beginMovementContext(@NotNull UUID playerId,
                                                                      @NotNull MovementContextType type,
                                                                      @NotNull String sourcePlugin,
                                                                      long durationMillis,
                                                                      String detail) {
        MovementContextManager manager =
                GrimAPI.INSTANCE.getMovementContextManager();

        if (type.allowsTrustedVelocityOverride()) {
            return manager.beginObserved(
                    playerId,
                    type,
                    sourcePlugin,
                    durationMillis,
                    detail
            );
        }

        return manager.beginTrusted(
                playerId,
                type,
                sourcePlugin,
                durationMillis,
                detail
        );
    }

    /**
     * Registers an exact plugin-authored velocity. Prefer this overload for
     * knockback/launch/dash/pull mechanics: it scopes trust to the supplied
     * vector instead of weakening unrelated combat velocities.
     */
    public static MovementContextManager.Context beginVelocityContext(
            @NotNull UUID playerId,
            @NotNull MovementContextType type,
            @NotNull String sourcePlugin,
            long durationMillis,
            double x,
            double y,
            double z,
            String detail
    ) {
        return beginVelocityContext(
                playerId,
                type,
                sourcePlugin,
                durationMillis,
                -1,
                x,
                y,
                z,
                detail
        );
    }

    /**
     * Transaction-aware velocity scope for integrations that can observe the
     * transaction associated with the server-authored impulse.
     */
    public static MovementContextManager.Context beginVelocityContext(
            @NotNull UUID playerId,
            @NotNull MovementContextType type,
            @NotNull String sourcePlugin,
            long durationMillis,
            int transaction,
            double x,
            double y,
            double z,
            String detail
    ) {
        return GrimAPI.INSTANCE.getMovementContextManager().beginTrustedVelocity(
                playerId,
                type,
                sourcePlugin,
                durationMillis,
                new Vector3dm(x, y, z),
                transaction,
                detail
        );
    }

    public static MovementContextManager.Context beginMagicSpellVelocityContext(
            @NotNull UUID playerId,
            @NotNull MovementContextType type,
            @NotNull String spellName,
            long durationMillis,
            double x,
            double y,
            double z
    ) {
        return beginVelocityContext(
                playerId,
                type,
                "MagicSpells:" + spellName,
                durationMillis,
                x,
                y,
                z,
                "spell=" + spellName
        );
    }

    public static MovementContextManager.Context beginRpgItemVelocityContext(
            @NotNull UUID playerId,
            @NotNull MovementContextType type,
            @NotNull String powerName,
            long durationMillis,
            double x,
            double y,
            double z
    ) {
        return beginVelocityContext(
                playerId,
                type,
                "RPGItems:" + powerName,
                durationMillis,
                x,
                y,
                z,
                "power=" + powerName
        );
    }

    public static MovementContextManager.Context beginCombatPlusVelocityContext(
            @NotNull UUID playerId,
            @NotNull MovementContextType type,
            @NotNull String mechanicName,
            long durationMillis,
            double x,
            double y,
            double z
    ) {
        return beginVelocityContext(
                playerId,
                type,
                "CombatPlus:" + mechanicName,
                durationMillis,
                x,
                y,
                z,
                "mechanic=" + mechanicName
        );
    }

    public static MovementContextManager.Context beginMagicSpellContext(@NotNull UUID playerId,
                                                                       @NotNull MovementContextType type,
                                                                       @NotNull String spellName,
                                                                       long durationMillis) {
        return beginMovementContext(playerId, type, "MagicSpells:" + spellName, durationMillis, "spell=" + spellName);
    }

    public static MovementContextManager.Context beginRpgItemContext(@NotNull UUID playerId,
                                                                     @NotNull MovementContextType type,
                                                                     @NotNull String powerName,
                                                                     long durationMillis) {
        return beginMovementContext(playerId, type, "RPGItems:" + powerName, durationMillis, "power=" + powerName);
    }

    public static MovementContextManager.Context beginCombatPlusMovementContext(@NotNull UUID playerId,
                                                                                @NotNull MovementContextType type,
                                                                                @NotNull String mechanicName,
                                                                                long durationMillis) {
        return beginMovementContext(playerId, type, "CombatPlus:" + mechanicName, durationMillis, "mechanic=" + mechanicName);
    }

    public static boolean hasMovementContext(@NotNull UUID playerId, @NotNull MovementContextType type) {
        return GrimAPI.INSTANCE.getMovementContextManager().hasActive(playerId, type);
    }

    public static @NotNull List<MovementContextManager.Context> getMovementContexts(@NotNull UUID playerId) {
        return GrimAPI.INSTANCE.getMovementContextManager().getActive(playerId);
    }

    public static void clearMovementContexts(@NotNull UUID playerId, @NotNull String sourcePlugin) {
        GrimAPI.INSTANCE.getMovementContextManager()
                .clearSourcePrefix(playerId, sourcePlugin);
    }

    public static void clearAllMovementContexts(@NotNull UUID playerId) {
        GrimAPI.INSTANCE.getMovementContextManager().clear(playerId);
    }


    public static InteractionContextManager.Context beginInteractionContext(@NotNull UUID playerId,
                                                                            @NotNull InteractionContextType type,
                                                                            @NotNull String sourcePlugin,
                                                                            long durationMillis) {
        return GrimAPI.INSTANCE.getInteractionContextManager().begin(playerId, type, sourcePlugin, durationMillis);
    }

    public static InteractionContextManager.Context beginInteractionContext(@NotNull UUID playerId,
                                                                            @NotNull InteractionContextType type,
                                                                            @NotNull String sourcePlugin,
                                                                            long durationMillis,
                                                                            String detail) {
        return GrimAPI.INSTANCE.getInteractionContextManager().begin(playerId, type, sourcePlugin, durationMillis, detail);
    }


    public static @NotNull List<InteractionContextManager.Context> getInteractionContexts(@NotNull UUID playerId) {
        return GrimAPI.INSTANCE.getInteractionContextManager().getActive(playerId);
    }

    public static void clearInteractionContexts(@NotNull UUID playerId, @NotNull String sourcePlugin) {
        GrimAPI.INSTANCE.getInteractionContextManager()
                .clearSourcePrefix(playerId, sourcePlugin);
    }

    public static void clearAllInteractionContexts(@NotNull UUID playerId) {
        GrimAPI.INSTANCE.getInteractionContextManager().clear(playerId);
    }

    /** Allows a trusted combat plugin to synchronize its own PvP tag with Grim's protected ledger. */
    public static void tagCombat(@NotNull UUID firstPlayerId, @NotNull UUID secondPlayerId) {
        GrimAPI.INSTANCE.getCombatIntegrityManager().tag(firstPlayerId, secondPlayerId);
    }

    public static void tagCombat(@NotNull UUID playerId) {
        GrimAPI.INSTANCE.getCombatIntegrityManager().tag(playerId);
    }

    /** Synchronize an external combat plugin using its exact remaining/new tag duration. */
    public static void tagCombat(@NotNull UUID firstPlayerId, @NotNull UUID secondPlayerId, long durationMillis) {
        GrimAPI.INSTANCE.getCombatIntegrityManager().tag(firstPlayerId, secondPlayerId, durationMillis);
    }

    public static void tagCombat(@NotNull UUID playerId, long durationMillis) {
        GrimAPI.INSTANCE.getCombatIntegrityManager().tag(playerId, durationMillis);
    }

    public static boolean isCombatTagged(@NotNull UUID playerId) {
        return GrimAPI.INSTANCE.getCombatIntegrityManager().isTagged(playerId);
    }

    public static long getRemainingCombatMillis(@NotNull UUID playerId) {
        return GrimAPI.INSTANCE.getCombatIntegrityManager().getRemainingMillis(playerId);
    }

    public static void clearCombat(@NotNull UUID playerId) {
        GrimAPI.INSTANCE.getCombatIntegrityManager().clearCombat(playerId);
    }

    /** Returns a point-in-time diagnostics snapshot without exposing mutable manager state. */
    public static @NotNull IntegritySnapshot getIntegritySnapshot(@NotNull UUID playerId) {
        return new IntegritySnapshot(
                GrimAPI.INSTANCE.getCombatIntegrityManager().isTagged(playerId),
                GrimAPI.INSTANCE.getCombatIntegrityManager().isStallHeld(playerId),
                GrimAPI.INSTANCE.getCombatIntegrityManager().getRemainingMillis(playerId),
                GrimAPI.INSTANCE.getFallIntegrityManager().getPendingDistance(playerId),
                GrimAPI.INSTANCE.getIntegrityCorrelationManager().getScore(playerId),
                GrimAPI.INSTANCE.getMovementContextManager().getActive(playerId),
                GrimAPI.INSTANCE.getInteractionContextManager().getActive(playerId)
        );
    }
}

