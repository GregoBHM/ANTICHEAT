package ac.grim.grimac.integration;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.manager.integrity.IntegritySnapshot;
import ac.grim.grimac.manager.integrity.InteractionContextManager;
import ac.grim.grimac.manager.integrity.InteractionContextType;
import ac.grim.grimac.manager.integrity.MovementContextManager;
import ac.grim.grimac.manager.integrity.MovementContextType;
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

    public static MovementContextManager.Context beginMovementContext(@NotNull UUID playerId,
                                                                      @NotNull MovementContextType type,
                                                                      @NotNull String sourcePlugin,
                                                                      long durationMillis) {
        return GrimAPI.INSTANCE.getMovementContextManager().begin(playerId, type, sourcePlugin, durationMillis);
    }

    public static MovementContextManager.Context beginMovementContext(@NotNull UUID playerId,
                                                                      @NotNull MovementContextType type,
                                                                      @NotNull String sourcePlugin,
                                                                      long durationMillis,
                                                                      String detail) {
        return GrimAPI.INSTANCE.getMovementContextManager().begin(playerId, type, sourcePlugin, durationMillis, detail);
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

    public static boolean hasMovementContext(@NotNull UUID playerId, @NotNull MovementContextType type) {
        return GrimAPI.INSTANCE.getMovementContextManager().hasActive(playerId, type);
    }

    public static @NotNull List<MovementContextManager.Context> getMovementContexts(@NotNull UUID playerId) {
        return GrimAPI.INSTANCE.getMovementContextManager().getActive(playerId);
    }

    public static void clearMovementContexts(@NotNull UUID playerId, @NotNull String sourcePlugin) {
        GrimAPI.INSTANCE.getMovementContextManager().clear(playerId, sourcePlugin);
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
        GrimAPI.INSTANCE.getInteractionContextManager().clear(playerId, sourcePlugin);
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

