package ac.grim.grimac.manager.integrity;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/** Built-in provider backed by SparkGrim's own combat ledger. */
public final class InternalCombatProvider implements CombatProvider {
    private final CombatIntegrityManager manager;

    InternalCombatProvider(CombatIntegrityManager manager) {
        this.manager = manager;
    }

    @Override public @NotNull String id() { return "internal"; }
    @Override public boolean isAvailable() { return manager.isEnabled(); }
    @Override public void tagPlayer(@NotNull UUID uuid, long durationMillis) { manager.tagInternal(uuid, durationMillis); }
    @Override public void untagPlayer(@NotNull UUID uuid) { manager.clearInternal(uuid); }
}
