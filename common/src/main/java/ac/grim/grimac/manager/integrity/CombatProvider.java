package ac.grim.grimac.manager.integrity;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/** Optional combat-tag bridge. Implementations must fail closed and never be required for Grim movement checks. */
public interface CombatProvider {
    @NotNull String id();
    boolean isAvailable();
    void tagPlayer(@NotNull UUID uuid, long durationMillis);
    void untagPlayer(@NotNull UUID uuid);
}
