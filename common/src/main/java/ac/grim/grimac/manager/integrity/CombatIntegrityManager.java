package ac.grim.grimac.manager.integrity;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.player.GrimPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Server-side combat ledger used by Blink protection. The internal ledger is always authoritative;
 * an optional CombatProvider may mirror tags to a combat plugin without becoming a hard dependency.
 */
public final class CombatIntegrityManager {
    private final Map<UUID, CombatState> states = new ConcurrentHashMap<>();
    private final Map<UUID, PendingPenalty> pendingPenalties = new ConcurrentHashMap<>();
    private final InternalCombatProvider internalProvider = new InternalCombatProvider(this);

    private volatile CombatProvider externalProvider;
    private volatile boolean enabled = true;
    private volatile long tagDurationNanos = TimeUnit.SECONDS.toNanos(15);
    private volatile long resumeMinimumNanos = TimeUnit.SECONDS.toNanos(3);
    private volatile long expiredStateRetentionNanos = TimeUnit.SECONDS.toNanos(10);
    private volatile long pendingPenaltyMillis = TimeUnit.MINUTES.toMillis(5);
    private volatile long providerRefreshNanos = TimeUnit.SECONDS.toNanos(1);
    private volatile boolean punishAllCombatQuits = false;
    private volatile boolean killOnUnsafeDisconnect = false;
    private volatile boolean killOnNextJoin = false;
    private volatile boolean blockCommands = true;
    private volatile boolean silentProtection = true;
    private volatile boolean externalIntegrationEnabled = true;
    private volatile boolean fallbackInternal = true;
    private volatile String blockMessage = "";
    private volatile List<String> allowedCommands = List.of("msg", "tell", "r");

    public void reload(@NotNull ConfigManager config) {
        enabled = config.getBooleanElse("combat-integrity.enabled", true);
        tagDurationNanos = TimeUnit.SECONDS.toNanos(clamp(config.getLongElse("combat-integrity.tag-seconds", 15L), 1L, 300L));
        resumeMinimumNanos = TimeUnit.MILLISECONDS.toNanos(clamp(config.getLongElse("combat-integrity.resume-minimum-tag-ms", 3000L), 0L, 60_000L));
        pendingPenaltyMillis = TimeUnit.SECONDS.toMillis(clamp(config.getLongElse("combat-integrity.pending-disconnect-window-seconds", 300L), 10L, 3600L));
        punishAllCombatQuits = config.getBooleanElse("combat-integrity.punish-all-combat-quits", false);
        killOnUnsafeDisconnect = config.getBooleanElse("combat-integrity.kill-on-unsafe-disconnect", false);
        killOnNextJoin = config.getBooleanElse("combat-integrity.kill-on-next-join-if-needed", false);
        blockCommands = config.getBooleanElse("combat-integrity.block-commands", true);
        silentProtection = config.getBooleanElse("combat-integrity.silent-protection", true);
        blockMessage = config.getStringElse("combat-integrity.block-message", "");
        externalIntegrationEnabled = config.getBooleanElse("combat-integrity.providers.combatplus.enabled", true);
        fallbackInternal = config.getBooleanElse("combat-integrity.providers.fallback-internal", true);
        providerRefreshNanos = TimeUnit.MILLISECONDS.toNanos(clamp(
                config.getLongElse("combat-integrity.providers.combatplus.refresh-during-protection-ms", 1000L), 250L, 10_000L));

        List<String> configured = config.getStringListElse("combat-integrity.allowed-commands", List.of("msg", "tell", "r"));
        List<String> normalized = new ArrayList<>();
        if (configured != null) {
            for (String command : configured) {
                if (command == null) continue;
                String clean = normalizeCommand(command);
                if (!clean.isEmpty()) normalized.add(clean);
            }
        }
        allowedCommands = Collections.unmodifiableList(normalized);

        if (!enabled) {
            states.clear();
            pendingPenalties.clear();
        }
    }

    public @NotNull CombatProvider getInternalProvider() { return internalProvider; }

    public void setExternalProvider(@Nullable CombatProvider provider) {
        externalProvider = provider;
    }

    public @NotNull String getProviderId() {
        CombatProvider provider = externalProvider;
        if (externalIntegrationEnabled && provider != null && safeAvailable(provider)) return provider.id();
        return internalProvider.id();
    }

    public void tag(@NotNull UUID first, @NotNull UUID second) {
        if (!enabled || first.equals(second)) return;
        long durationMillis = TimeUnit.NANOSECONDS.toMillis(tagDurationNanos);
        internalProvider.tagPlayer(first, durationMillis);
        internalProvider.tagPlayer(second, durationMillis);
        mirrorTag(first, durationMillis);
        mirrorTag(second, durationMillis);
    }

    public void tag(@NotNull UUID first, @NotNull UUID second, long durationMillis) {
        if (!enabled || first.equals(second)) return;
        long duration = clamp(durationMillis, 1L, 300_000L);
        internalProvider.tagPlayer(first, duration);
        internalProvider.tagPlayer(second, duration);
        mirrorTag(first, duration);
        mirrorTag(second, duration);
    }

    public void tag(@NotNull UUID uuid) {
        if (!enabled) return;
        long durationMillis = TimeUnit.NANOSECONDS.toMillis(tagDurationNanos);
        internalProvider.tagPlayer(uuid, durationMillis);
        mirrorTag(uuid, durationMillis);
    }

    public void tag(@NotNull UUID uuid, long durationMillis) {
        if (!enabled) return;
        long duration = clamp(durationMillis, 1L, 300_000L);
        internalProvider.tagPlayer(uuid, duration);
        mirrorTag(uuid, duration);
    }

    void tagInternal(@NotNull UUID uuid, long durationMillis) {
        long durationNanos = TimeUnit.MILLISECONDS.toNanos(clamp(durationMillis, 1L, 300_000L));
        long now = System.nanoTime();
        CombatState state = states.computeIfAbsent(uuid, ignored -> new CombatState());
        synchronized (state) {
            state.disconnected = false;
            state.disconnectCleanupAtMillis = 0L;
            if (state.stallHold) state.heldRemainingNanos = Math.max(state.heldRemainingNanos, durationNanos);
            else state.combatUntilNanos = Math.max(state.combatUntilNanos, now + durationNanos);
        }
    }

    void clearInternal(@NotNull UUID uuid) {
        states.remove(uuid);
        pendingPenalties.remove(uuid);
    }

    public void beginStall(
            @NotNull GrimPlayer player,
            long stallStartNanos,
            boolean selectiveEvidence
    ) {
        if (!enabled) return;
        UUID uuid = player.uuid;
        CombatState state = states.get(uuid);
        if (state == null) return;
        boolean refreshExternal = false;
        synchronized (state) {
            long remainingAtStart = state.stallHold
                    ? state.heldRemainingNanos
                    : Math.max(0L, state.combatUntilNanos - stallStartNanos);
            if (remainingAtStart <= 0L) return;
            if (!state.stallHold) {
                state.heldRemainingNanos = remainingAtStart;
                state.stallHold = true;
                state.holdStartedNanos = stallStartNanos;
                refreshExternal = true;
            } else {
                state.heldRemainingNanos = Math.max(
                        state.heldRemainingNanos,
                        remainingAtStart
                );
            }
            state.protectedSession = true;
            state.selectiveEvidence |= selectiveEvidence;
        }
        if (refreshExternal) refreshExternalTag(player);
    }

    public void refreshProtectedProviderTag(@NotNull GrimPlayer player) {
        if (!enabled || !externalIntegrationEnabled) return;
        UUID uuid = player.uuid;
        CombatState state = states.get(uuid);
        if (state == null) return;
        long now = System.nanoTime();
        boolean refresh = false;
        synchronized (state) {
            if (state.stallHold
                    && now - state.lastProviderRefreshNanos >= providerRefreshNanos) {
                state.lastProviderRefreshNanos = now;
                refresh = true;
            }
        }
        if (refresh) refreshExternalTag(player);
    }

    /**
     * ConnectionStall may reach this path from PacketEvents/async polling.
     * External Bukkit plugin APIs must therefore be invoked on the player's
     * entity scheduler rather than directly from the integrity thread.
     */
    private void refreshExternalTag(@NotNull GrimPlayer player) {
        CombatProvider provider = externalProvider;
        if (!externalIntegrationEnabled
                || provider == null
                || player.platformPlayer == null) {
            return;
        }

        UUID uuid = player.uuid;
        long remaining = Math.max(1L, getRemainingMillis(uuid));

        GrimAPI.INSTANCE.getScheduler().getEntityScheduler().execute(
                player.platformPlayer,
                GrimAPI.INSTANCE.getGrimPlugin(),
                () -> {
                    if (!safeAvailable(provider)) return;
                    try {
                        provider.tagPlayer(uuid, remaining);
                    } catch (RuntimeException ignored) {
                        if (!fallbackInternal) {
                            externalProvider = null;
                        }
                    }
                },
                null,
                0
        );
    }

    public void markSelectiveEvidence(@NotNull UUID uuid) {
        CombatState state = states.get(uuid);
        if (state == null) return;
        synchronized (state) {
            if (state.stallHold || isTaggedLocked(state, System.nanoTime())) {
                state.selectiveEvidence = true;
                state.protectedSession = true;
            }
        }
    }

    /**
     * Destructive combat-logout consequences require an accepted anti-cheat
     * violation, not merely prevention-only/selective suspicion.
     */
    public void markSanctionableEvidence(@NotNull UUID uuid) {
        CombatState state = states.get(uuid);
        if (state == null) return;
        synchronized (state) {
            if (state.stallHold || isTaggedLocked(state, System.nanoTime())) {
                state.sanctionableEvidence = true;
                state.protectedSession = true;
            }
        }
    }

    public void releaseStall(@NotNull UUID uuid) { releaseStall(uuid, true); }

    public void releaseStall(@NotNull UUID uuid, boolean enforceResumeMinimum) {
        if (!enabled) return;
        CombatState state = states.get(uuid);
        if (state == null) return;
        synchronized (state) {
            if (!state.stallHold) return;
            long remaining = enforceResumeMinimum ? Math.max(state.heldRemainingNanos, resumeMinimumNanos) : state.heldRemainingNanos;
            state.stallHold = false;
            state.heldRemainingNanos = 0L;
            state.holdStartedNanos = 0L;
            state.lastProviderRefreshNanos = 0L;
            state.combatUntilNanos = System.nanoTime() + Math.max(0L, remaining);
        }
    }

    public boolean isTagged(@NotNull UUID uuid) {
        if (!enabled) return false;
        CombatState state = states.get(uuid);
        if (state == null) return false;
        synchronized (state) { return state.stallHold || isTaggedLocked(state, System.nanoTime()); }
    }

    public boolean wasTaggedAt(@NotNull UUID uuid, long instantNanos) {
        if (!enabled) return false;
        CombatState state = states.get(uuid);
        if (state == null) return false;
        synchronized (state) { return state.stallHold || state.combatUntilNanos > instantNanos; }
    }

    public boolean isStallHeld(@NotNull UUID uuid) {
        CombatState state = states.get(uuid);
        if (state == null) return false;
        synchronized (state) { return state.stallHold; }
    }

    public boolean isProtectedSession(@NotNull UUID uuid) {
        if (!enabled) return false;
        CombatState state = states.get(uuid);
        if (state == null) return false;
        synchronized (state) { return state.protectedSession && (state.stallHold || isTaggedLocked(state, System.nanoTime())); }
    }

    public long getRemainingMillis(@NotNull UUID uuid) {
        CombatState state = states.get(uuid);
        if (state == null) return 0L;
        synchronized (state) {
            long remaining = state.stallHold ? state.heldRemainingNanos : Math.max(0L, state.combatUntilNanos - System.nanoTime());
            return TimeUnit.NANOSECONDS.toMillis(remaining);
        }
    }

    /** Ambiguous full freezes do not create an anti-cheat combat-logout penalty. */
    public boolean shouldPunishIntegrityDisconnect(@NotNull UUID uuid) {
        if (!enabled) return false;
        CombatState state = states.get(uuid);
        if (state == null) return false;
        synchronized (state) {
            boolean active = state.stallHold
                    || isTaggedLocked(state, System.nanoTime());
            return CombatDisconnectPolicy.shouldPunish(
                    active,
                    punishAllCombatQuits,
                    state.sanctionableEvidence
            );
        }
    }

    public void recordDisconnect(@NotNull UUID uuid) {
        if (!enabled) return;
        if (shouldPunishIntegrityDisconnect(uuid)) {
            CombatState state = states.get(uuid);
            boolean sanctionable = false;
            long remaining = 0L;
            if (state != null) {
                synchronized (state) {
                    sanctionable = state.sanctionableEvidence;
                    remaining = state.stallHold
                            ? state.heldRemainingNanos
                            : Math.max(
                                    0L,
                                    state.combatUntilNanos - System.nanoTime()
                            );
                }
            }
            long now = System.currentTimeMillis();
            pendingPenalties.put(
                    uuid,
                    new PendingPenalty(
                            now + pendingPenaltyMillis,
                            sanctionable,
                            remaining
                    )
            );
            GrimAPI.INSTANCE.getIntegrityCorrelationManager().record(uuid, IntegritySignal.UNSAFE_DISCONNECT);
            if (state != null) {
                synchronized (state) {
                    state.disconnected = true;
                    state.disconnectCleanupAtMillis = now + 5000L;
                }
            }
        } else {
            states.remove(uuid);
        }
    }

    public void resolveUnsafeDisconnect(@NotNull UUID uuid) {
        states.remove(uuid);
        pendingPenalties.remove(uuid);
        mirrorUntag(uuid);
    }

    public void clearCombat(@NotNull UUID uuid) {
        internalProvider.untagPlayer(uuid);
        mirrorUntag(uuid);
    }

    public boolean consumePendingJoinPenalty(@NotNull UUID uuid) {
        if (!enabled || !killOnNextJoin) return false;
        PendingPenalty pending = pendingPenalties.remove(uuid);
        if (pending == null || pending.expiresAtMillis < System.currentTimeMillis()) return false;
        states.remove(uuid);
        return true;
    }

    public boolean shouldBlockCommand(@NotNull UUID uuid, @NotNull String message) {
        if (!enabled || !blockCommands || !isProtectedSession(uuid)) return false;
        String command = normalizeCommand(message);
        if (command.isEmpty()) return false;
        for (String allowed : allowedCommands) if (command.equals(allowed)) return false;
        return true;
    }

    public void tick() {
        if (!enabled) return;
        long nowNanos = System.nanoTime();
        long nowMillis = System.currentTimeMillis();
        states.entrySet().removeIf(entry -> {
            CombatState state = entry.getValue();
            synchronized (state) {
                if (state.disconnected && state.disconnectCleanupAtMillis <= nowMillis) return true;
                return !state.stallHold && state.combatUntilNanos + expiredStateRetentionNanos <= nowNanos;
            }
        });
        pendingPenalties.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis < nowMillis);
    }

    public boolean isEnabled() { return enabled; }
    public boolean isKillOnUnsafeDisconnect() { return killOnUnsafeDisconnect; }
    public boolean isBlockCommands() { return blockCommands; }
    public boolean isSilentProtection() { return silentProtection; }
    public String getBlockMessage() { return blockMessage; }

    private void mirrorTag(UUID uuid, long durationMillis) {
        CombatProvider provider = externalProvider;
        if (!externalIntegrationEnabled || provider == null || !safeAvailable(provider)) return;
        try { provider.tagPlayer(uuid, durationMillis); }
        catch (RuntimeException ignored) { if (!fallbackInternal) externalProvider = null; }
    }

    private void mirrorUntag(UUID uuid) {
        CombatProvider provider = externalProvider;
        if (!externalIntegrationEnabled || provider == null || !safeAvailable(provider)) return;
        try { provider.untagPlayer(uuid); }
        catch (RuntimeException ignored) { if (!fallbackInternal) externalProvider = null; }
    }

    private static boolean safeAvailable(CombatProvider provider) {
        try { return provider.isAvailable(); }
        catch (RuntimeException ignored) { return false; }
    }

    private static boolean isTaggedLocked(CombatState state, long now) { return state.combatUntilNanos > now; }

    private static String normalizeCommand(String raw) {
        String command = raw.trim().toLowerCase(Locale.ROOT);
        if (command.startsWith("/")) command = command.substring(1);
        int space = command.indexOf(' ');
        if (space != -1) command = command.substring(0, space);
        int colon = command.indexOf(':');
        if (colon != -1 && colon + 1 < command.length()) command = command.substring(colon + 1);
        return command;
    }

    private static long clamp(long value, long min, long max) { return Math.max(min, Math.min(max, value)); }

    private static final class CombatState {
        long combatUntilNanos;
        boolean stallHold;
        long heldRemainingNanos;
        long holdStartedNanos;
        long lastProviderRefreshNanos;
        boolean protectedSession;
        boolean selectiveEvidence;
        boolean sanctionableEvidence;
        boolean disconnected;
        long disconnectCleanupAtMillis;
    }

    private static final class PendingPenalty {
        final long expiresAtMillis;
        final boolean sanctionableEvidence;
        final long remainingNanos;

        PendingPenalty(
                long expiresAtMillis,
                boolean sanctionableEvidence,
                long remainingNanos
        ) {
            this.expiresAtMillis = expiresAtMillis;
            this.sanctionableEvidence = sanctionableEvidence;
            this.remainingNanos = remainingNanos;
        }
    }
}
