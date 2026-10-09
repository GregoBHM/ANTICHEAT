package ac.grim.grimac.manager.config.update;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Centralised registry of {@link ConfigUpdater.Spec} instances for every
 * Grim config file.
 */
public final class GrimConfigSpecs {

    private GrimConfigSpecs() {
    }

    /**
     * Spec for the main config.yml.
     *
     * v9 -> v10: migrates Grim 2.x history settings into the split database files.
     * v10 -> v11: adds update-permission-ticks.
     * v11 -> v12: adds SparkGrim integrity/alert/interaction policy sections.
     * v12 -> v13: adds Phase 4A precision controls.
     * v13 -> v14: adds Phase 4B Blink profiles, recovery state,
     * MovementReleaseGuard, silent protection and optional CombatPlus provider settings.
     * v14 -> v15: adds Phase 4B.1 exact collision-source protection and
     * configurable protected materials/tags.
     * v15 -> v16: adds movement advantage prevention with safe-position quarantine
     * and severe prediction setback.
     * v16 -> v17: adds short-Blink release mitigation using packet-gap,
     * transaction-progress and burst-distance evidence without turning a short
     * network hiccup into a standalone punishment signal.
     * v17 -> v18: makes movement enforcement environment-aware, adds stuck
     * movement context/recovery, and keeps aggressive Blink mitigation out of
     * legitimate special-physics windows.
     * v18 -> v19: adds hard Blink release prevention, correlated TimerLimit packet
     * cancellation, queued use/interact blocking, and server-side FastConsume prevention.
     * v19 -> v20: makes Blink release mitigation one-shot and pre-prediction,
     * requires sub-tick burst confirmation, rebases TimerA/TimerLimit/Simulation
     * after rollback, and prevents residual setbacks while normal movement recovers.
     * v20 -> v21: adds staff-only correction diagnostics at the central movement
     * correction path so every real rollback/resync can be tied to the most recent
     * check and environment without adding VL or executing punishments.
     * v21 -> v22: centralizes special-environment correction policy, converts
     * NoFall/GroundSpoof to state enforcement, makes AntiKB velocity-owned, and
     * adds sanctionable packet-driven Regen correlation.
     * v22 -> v24: production-safe Blink ownership with short/action-first and
     * long-selective mitigation, prevention/sanction separation, one-shot
     * TimerLimit correction, and conservative full-freeze PvP handling.
     * v22 -> v23: makes Blink prevention own selective releases independently
     * from player RTT sanction confidence, protects candidate/full-freeze queued
     * actions, removes the long-stall prevention bypass, and verifies rollback
     * ownership before suppressing competing movement corrections.
     * v24 -> v25: player-safety hardening for legacy Blink. BadPacketsE observes
     * raw pre-prediction movement, action-first prevention becomes opt-in,
     * recovery cannot re-lock itself, and item-use ownership is left exclusively
     * to ConsumeTiming/NoSlow instead of StallActions.
     */
    public static @NotNull ConfigUpdater.Spec mainConfig() {
        return ConfigUpdater.Spec.builder("/config/", 28, ConfigUpdater.ConfigFlavor.V2)
                .migration(10, ctx -> {
                    String typeRaw = ctx.input().getString("history.database.type");
                    String type = typeRaw == null ? null : typeRaw.trim().toUpperCase(Locale.ROOT);
                    String backendId = backendIdFor(type);

                    if (backendId != null) {
                        for (String cat : new String[]{
                                "violation",
                                "session",
                                "player-identity",
                                "setting"
                        }) {
                            ctx.otherFile("database.yml")
                                    .put("database.routing." + cat, backendId);
                        }
                    }

                    Integer entriesPerPage =
                            ctx.input().getInt("history.entries-per-page");

                    if (entriesPerPage != null) {
                        ctx.otherFile("database.yml")
                                .put("database.history.entries-per-page", entriesPerPage);
                    }

                    String serverName =
                            ctx.input().getString("history.server-name");

                    if (serverName != null) {
                        ctx.otherFile("database.yml")
                                .put("database.server-name", serverName);
                    }

                    if (backendId != null && !backendId.equals("sqlite")) {
                        String target = "databases/" + backendId + ".yml";

                        Object host = ctx.input().get("history.database.host");
                        Object port = ctx.input().get("history.database.port");
                        Object db = ctx.input().get("history.database.database");
                        Object user = ctx.input().get("history.database.username");
                        Object pass = ctx.input().get("history.database.password");

                        if (host != null) {
                            ctx.otherFile(target).put(backendId + ".host", host);
                        }

                        if (port != null) {
                            ctx.otherFile(target).put(backendId + ".port", port);
                        }

                        if (db != null) {
                            ctx.otherFile(target).put(backendId + ".database", db);
                        }

                        if (user != null) {
                            ctx.otherFile(target).put(backendId + ".user", user);
                        }

                        if (pass != null) {
                            ctx.otherFile(target).put(backendId + ".password", pass);
                        }
                    }
                })
                .migration(25, ctx ->
                        ctx.output().put(
                                "blink-mitigation.short-selective-action-guard.enabled",
                                false
                        )
                )
                .migration(26, ctx ->
                        ctx.output().put(
                                "correction-diagnostics.format",
                                "[alert] &7%verbose%"
                        )
                )
                .migration(27, ctx ->
                        ctx.output().put(
                                "Knockback.enforcement.enabled",
                                false
                        )
                )
                .migration(28, ctx -> {
                    ctx.output().put(
                            "combat-integrity.kill-on-unsafe-disconnect",
                            false
                    );
                    ctx.output().put(
                            "combat-integrity.kill-on-next-join-if-needed",
                            false
                    );
                })
                .build();
    }

    private static @Nullable String backendIdFor(@Nullable String legacyType) {
        if (legacyType == null) {
            return null;
        }

        return switch (legacyType) {
            case "SQLITE" -> "sqlite";
            case "MYSQL" -> "mysql";
            case "POSTGRESQL", "POSTGRES" -> "postgres";
            default -> null;
        };
    }

    public static @NotNull ConfigUpdater.Spec discord() {
        return ConfigUpdater.Spec.builder(
                        "/discord/",
                        1,
                        ConfigUpdater.ConfigFlavor.V2
                )
                .build();
    }

    public static @NotNull ConfigUpdater.Spec messages() {
        return ConfigUpdater.Spec.builder(
                        "/messages/",
                        4,
                        ConfigUpdater.ConfigFlavor.V2
                )
                .migration(4, ctx -> {
                    ctx.output().put("verbose-format", "[alert] &7%verbose%");
                    ctx.output().put("alerts-format-proxy", "&8[proxy] [alert]");
                })
                .build();
    }

    public static @NotNull ConfigUpdater.Spec database() {
        return ConfigUpdater.Spec.builder(
                        "/database/",
                        1,
                        ConfigUpdater.ConfigFlavor.V2
                )
                .build();
    }

    public static @NotNull ConfigUpdater.Spec backend(
            @NotNull String backendId
    ) {
        ConfigUpdater.Spec.Builder builder =
                ConfigUpdater.Spec.builder(
                        "/databases/" + backendId + "/",
                        backendVersion(backendId),
                        ConfigUpdater.ConfigFlavor.V2
                );

        if (backendSupportsHikariPoolSettings(backendId)) {
            builder.migration(
                    2,
                    ctx -> preservePoolSettingOverrides(ctx, backendId)
            );
        }

        return builder.build();
    }

    private static int backendVersion(@NotNull String backendId) {
        return backendSupportsHikariPoolSettings(backendId) ? 2 : 1;
    }

    private static boolean backendSupportsHikariPoolSettings(
            @NotNull String backendId
    ) {
        return backendId.equals("mysql")
                || backendId.equals("postgres");
    }

    private static void preservePoolSettingOverrides(
            @NotNull MigrationContext ctx,
            @NotNull String backendId
    ) {
        String prefix = backendId + ".pool-settings.";

        for (String key : new String[]{
                "maximum-pool-size",
                "minimum-idle",
                "maximum-lifetime-ms",
                "keepalive-time-ms",
                "connection-timeout-ms"
        }) {
            Object value = ctx.input().get(prefix + key);

            if (value != null) {
                ctx.output().put(prefix + key, value);
            }
        }
    }
}
