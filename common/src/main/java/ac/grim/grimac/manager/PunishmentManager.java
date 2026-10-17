package ac.grim.grimac.manager;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.AbstractCheck;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.api.config.ConfigReloadable;
import ac.grim.grimac.api.event.events.CommandExecuteEvent;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.manager.integrity.SanctionEpisodeManager;
import ac.grim.grimac.events.packets.ProxyAlertMessenger;
import ac.grim.grimac.platform.api.player.PlatformPlayer;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.LogUtil;
import ac.grim.grimac.utils.anticheat.MessageUtil;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import net.kyori.adventure.text.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

public class PunishmentManager implements ConfigReloadable {
    private static final CommandExecuteEvent.Channel COMMAND_CHANNEL =
            GrimAPI.INSTANCE.getEventBus().get(CommandExecuteEvent.class);

    private final GrimPlayer player;
    private final List<PunishGroup> groups = new ArrayList<>();
    private final Set<String> canonicalCheckNames = new java.util.HashSet<>();

    private String experimentalSymbol = "*";
    private String alertString;
    private String verboseAlertString;
    private boolean testMode;
    private String proxyAlertString = "";

    // Raw VL remains complete for staff/history. This second ledger contains
    // only flags observed while both the server and player network are healthy
    // enough to support automatic sanctions.
    private double sanctionMinimumConfidence = 0.80D;
    private double sanctionMinimumServerConfidence = 0.80D;
    private double sanctionMinimumPlayerConfidence = 0.80D;
    private double sanctionMinimumTps = 18.0D;
    private boolean legacyOneEightNativeSensitiveSanctionsOnly = true;

    // v21: staff-only diagnostic alerts emitted only when Grim actually sends a
    // movement correction. They never add VL and never execute punishments.
    private CorrectionDiagnosticMode correctionDiagnosticsMode =
            CorrectionDiagnosticMode.VERBOSE;
    private long correctionDiagnosticsCooldownMillis = 125L;
    private String correctionDiagnosticsFormat;
    private final Map<String, Long> correctionDiagnosticsCooldowns =
            new java.util.concurrent.ConcurrentHashMap<>();

    public PunishmentManager(GrimPlayer player) {
        this.player = player;
    }

    @Override
    public void reload(ConfigManager config) {
        List<?> punish =
                config.getStringListElse("Punishments", new ArrayList<>());

        experimentalSymbol =
                config.getStringElse("experimental-symbol", "*");

        alertString = config.getStringElse(
                "alerts-format",
                "%prefix% &f%player% &bfailed &f%check_name%%experimental% "
                        + "&f(x&c%vl%&f) &8[%severity%]"
        );

        verboseAlertString = config.getStringElse(
                "verbose-format",
                "[alert] &7%verbose%"
        );

        boolean legacyCorrectionDiagnosticsEnabled =
                config.getBooleanElse("correction-diagnostics.enabled", true);
        correctionDiagnosticsMode = CorrectionDiagnosticMode.parse(
                config.getString(
                        "correction-diagnostics.mode"
                ),
                legacyCorrectionDiagnosticsEnabled
        );

        correctionDiagnosticsCooldownMillis = Math.max(0L, Math.min(5000L,
                config.getLongElse("correction-diagnostics.cooldown-ms", 125L)));

        correctionDiagnosticsFormat = config.getStringElse(
                "correction-diagnostics.format",
                "&8[correction] &7%check_name% &8[%component%] &f%verbose%"
        );

        correctionDiagnosticsCooldowns.clear();

        testMode =
                config.getBooleanElse("test-mode", false);

        proxyAlertString = config.getStringElse(
                "alerts-format-proxy",
                "&8[proxy] [alert]"
        );

        sanctionMinimumConfidence = clamp01(config.getDoubleElse(
                "sanctions.minimum-confidence",
                0.80D
        ));
        sanctionMinimumServerConfidence = clamp01(config.getDoubleElse(
                "sanctions.minimum-server-confidence",
                0.80D
        ));
        sanctionMinimumPlayerConfidence = clamp01(config.getDoubleElse(
                "sanctions.minimum-player-confidence",
                0.80D
        ));
        sanctionMinimumTps = Math.max(
                1.0D,
                Math.min(
                        20.0D,
                        config.getDoubleElse("sanctions.minimum-tps", 18.0D)
                )
        );
        legacyOneEightNativeSensitiveSanctionsOnly = config.getBooleanElse(
                "sanctions.legacy-1_8.native-sensitive-sanctions-only",
                true
        );

        try {
            groups.clear();
            canonicalCheckNames.clear();

            for (AbstractCheck check : player.getChecks()) {
                check.setEnabled(false);
                if (check.getCheckName() != null
                        && !check.getCheckName().isBlank()) {
                    canonicalCheckNames.add(
                            check.getCheckName()
                                    .trim()
                                    .toLowerCase(Locale.ROOT)
                    );
                }
            }

            for (Object rawGroup : punish) {
                if (!(rawGroup instanceof Map<?, ?> map)) {
                    continue;
                }

                List<String> checks =
                        stringList(map.get("checks"));

                List<String> commands =
                        stringList(map.get("commands"));

                int removeViolationsAfter =
                        numberOrDefault(
                                map.get("remove-violations-after"),
                                300
                        );

                Object thresholdScopeRaw = map.get("threshold-scope");

                List<ParsedCommand> parsed =
                        new ArrayList<>();

                List<AbstractCheck> checksList =
                        new ArrayList<>();

                List<AbstractCheck> excluded =
                        new ArrayList<>();

                for (String configuredCheck : checks) {
                    String command =
                            configuredCheck.toLowerCase(Locale.ROOT).trim();

                    boolean exclude = false;

                    if (command.startsWith("!")) {
                        exclude = true;
                        command = command.substring(1).trim();
                    }

                    List<AbstractCheck> matched =
                            selectConfiguredChecks(command);

                    for (AbstractCheck check : matched) {
                        if (exclude) {
                            if (!excluded.contains(check)) {
                                excluded.add(check);
                            }
                        } else if (!checksList.contains(check)) {
                            checksList.add(check);
                        }
                    }

                    for (AbstractCheck check : excluded) {
                        checksList.remove(check);
                    }
                }

                ThresholdScope thresholdScope =
                        thresholdScopeRaw == null
                                && defaultsToPerCheckScope(checksList)
                                ? ThresholdScope.CHECK
                                : ThresholdScope.parse(thresholdScopeRaw);

                for (AbstractCheck check : checksList) {
                    check.setEnabled(true);
                }

                for (String command : commands) {
                    parsed.add(parseCommand(command));
                }

                groups.add(
                        new PunishGroup(
                                checksList,
                                parsed,
                                removeViolationsAfter * 1000,
                                thresholdScope
                        )
                );
            }
        } catch (Exception e) {
            LogUtil.error(
                    "Error while loading punishments.yml! "
                            + "Check punishment thresholds/conditions.",
                    e
            );
        }
    }

    private ParsedCommand parseCommand(String raw) {
        int firstColon = raw.indexOf(':');
        int firstSpace =
                firstColon < 0
                        ? -1
                        : raw.indexOf(' ', firstColon + 1);

        if (firstColon <= 0 || firstSpace <= firstColon + 1) {
            throw new IllegalArgumentException(
                    "Invalid punishment command '" + raw
                            + "'. Expected 'threshold:interval command'."
            );
        }

        int threshold =
                Integer.parseInt(
                        raw.substring(0, firstColon).trim()
                );

        int interval =
                Integer.parseInt(
                        raw.substring(firstColon + 1, firstSpace).trim()
                );

        if (threshold < 0 || interval < 0) {
            throw new IllegalArgumentException(
                    "Punishment threshold and interval must be >= 0: "
                            + raw
            );
        }

        String action =
                raw.substring(firstSpace + 1).trim();

        PunishmentCondition condition = null;
        boolean unless = false;

        String lower =
                action.toLowerCase(Locale.ROOT);

        String conditionPrefix = null;

        if (lower.startsWith("if ")) {
            conditionPrefix = "if ";
        } else if (lower.startsWith("unless ")) {
            conditionPrefix = "unless ";
            unless = true;
        }

        if (conditionPrefix != null) {
            int doIndex =
                    lower.indexOf(" do ", conditionPrefix.length());

            if (doIndex < 0) {
                throw new IllegalArgumentException(
                        "Conditional punishment command is missing ' do ': "
                                + raw
                );
            }

            String expression =
                    action.substring(
                            conditionPrefix.length(),
                            doIndex
                    ).trim();

            action =
                    action.substring(doIndex + 4).trim();

            if (action.isEmpty()) {
                throw new IllegalArgumentException(
                        "Conditional punishment command has no action: "
                                + raw
                );
            }

            condition =
                    PunishmentCondition.parse(expression);
        }

        return new ParsedCommand(
                threshold,
                interval,
                action,
                condition,
                unless
        );
    }

    private String replaceAlertPlaceholders(
            String original,
            int vl,
            int groupVl,
            Check check,
            String verbose
    ) {
        return replaceAlertPlaceholders(
                original,
                vl,
                groupVl,
                check,
                verbose,
                0
        );
    }

    private String replaceAlertPlaceholders(
            String original,
            int vl,
            int groupVl,
            Check check,
            String verbose,
            int suppressed
    ) {
        return replaceAlertPlaceholders(
                original,
                vl,
                groupVl,
                check.getDisplayName(),
                check.isExperimental(),
                check.getDescription(),
                check.getStableKey(),
                componentFor(check),
                verbose,
                suppressed
        ).replace(
                "%packet_modify%",
                check.shouldModifyPackets() ? "ACTIVE" : "BYPASS"
        ).replace(
                "%setback_access%",
                check.isNoSetbackPermission() ? "BYPASS" : "ACTIVE"
        );
    }

    private String replaceAlertPlaceholders(
            String original,
            int vl,
            int groupVl,
            String sourceName,
            boolean experimental,
            String description,
            String stableKey,
            String component,
            String verbose,
            int suppressed
    ) {
        String severity =
                GrimAPI.INSTANCE.getAlertAggregationManager()
                        .severity(player.uuid, vl)
                        .name();

        String episodeFamily = SanctionEpisodeManager.familyFor(
                stableKey,
                component
        );
        int confirmedEpisodes10m = GrimAPI.INSTANCE
                .getSanctionEpisodeManager()
                .countWithin(
                        player.uuid,
                        episodeFamily,
                        TimeUnit.MINUTES.toMillis(10L)
                );
        int confirmedEpisodes2h = GrimAPI.INSTANCE
                .getSanctionEpisodeManager()
                .countWithin(
                        player.uuid,
                        episodeFamily,
                        TimeUnit.HOURS.toMillis(2L)
                );
        int confirmedEpisodesRetention = GrimAPI.INSTANCE
                .getSanctionEpisodeManager()
                .countRetention(player.uuid, episodeFamily);
        double episodeConfidence = GrimAPI.INSTANCE
                .getSanctionEpisodeManager()
                .latestConfidence(player.uuid, episodeFamily);
        boolean episodeRecovery = GrimAPI.INSTANCE
                .getSanctionEpisodeManager()
                .isRecoveryBlocked(player.uuid);
        String episodeRecoveryReason = GrimAPI.INSTANCE
                .getSanctionEpisodeManager()
                .recoveryReason(player.uuid);

        String template = StaffAlertTemplate.expand(
                original,
                alertString,
                verboseAlertString,
                proxyAlertString
        );

        return MessageUtil.replacePlaceholders(
                player,
                template
                        .replace("%check_name%", sourceName == null ? "Unknown" : sourceName)
                        .replace(
                                "%experimental%",
                                experimental
                                        ? experimentalSymbol
                                        : ""
                        )
                        .replace("%vl%", Integer.toString(vl))
                        .replace("%check_vl%", Integer.toString(vl))
                        .replace("%group_vl%", Integer.toString(groupVl))
                        .replace("%description%", description == null ? "" : description)
                        .replace("%stable_key%", stableKey == null ? "" : stableKey)
                        .replace("%component%", component == null ? "Other" : component)
                        .replace("%severity%", severity)
                        .replace(
                                "%confidence%",
                                formatMetric(
                                        GrimAPI.INSTANCE.getLagProtectionManager()
                                                .heuristicConfidence(player)
                                )
                        )
                        .replace(
                                "%server_confidence%",
                                formatMetric(
                                        GrimAPI.INSTANCE.getLagProtectionManager()
                                                .heuristicConfidence()
                                )
                        )
                        .replace(
                                "%player_confidence%",
                                formatMetric(
                                        GrimAPI.INSTANCE.getLagProtectionManager()
                                                .playerNetworkConfidence(player)
                                )
                        )
                        .replace("%ping%", Integer.toString(player.getTransactionPing()))
                        .replace(
                                "%avg_ping%",
                                formatMetric(
                                        GrimAPI.INSTANCE.getLagProtectionManager()
                                                .getAveragePingMillis(player.uuid)
                                )
                        )
                        .replace(
                                "%jitter%",
                                formatMetric(
                                        GrimAPI.INSTANCE.getLagProtectionManager()
                                                .getPlayerJitterMillis(player.uuid)
                                )
                        )
                        .replace(
                                "%tps%",
                                formatMetric(
                                        GrimAPI.INSTANCE.getPlatformServer().getTPS()
                                )
                        )
                        .replace(
                                "%integrity_score%",
                                formatMetric(
                                        GrimAPI.INSTANCE.getIntegrityCorrelationManager()
                                                .getScore(player.uuid)
                                )
                        )
                        .replace(
                                "%confirmed_episodes_10m%",
                                Integer.toString(confirmedEpisodes10m)
                        )
                        .replace(
                                "%confirmed_episodes_2h%",
                                Integer.toString(confirmedEpisodes2h)
                        )
                        .replace(
                                "%confirmed_episodes%",
                                Integer.toString(confirmedEpisodesRetention)
                        )
                        .replace(
                                "%confirmed_episodes_retention%",
                                Integer.toString(confirmedEpisodesRetention)
                        )
                        .replace(
                                "%episode_confidence%",
                                formatMetric(episodeConfidence)
                        )
                        .replace(
                                "%episode_recovery%",
                                episodeRecovery ? "YES" : "NO"
                        )
                        .replace(
                                "%episode_recovery_reason%",
                                episodeRecoveryReason
                        )
                        .replace("%lag_state%", lagState())
                        .replace(
                                "%suppressed%",
                                Integer.toString(
                                        Math.max(0, suppressed)
                                )
                        )
                        .replace(
                                "%suppressed_suffix%",
                                GrimAPI.INSTANCE
                                        .getAlertAggregationManager()
                                        .suppressedSuffix(suppressed)
                        )
        ).replace(
                "%verbose%",
                MessageUtil.miniMessageSafe(verbose == null ? "" : verbose)
        );
    }

    /**
     * v21 movement-correction diagnostic. This deliberately bypasses punishment
     * thresholds and does not call handleViolation(): it is only a staff alert
     * so a real movement correction can carry its explicit source without adding VL.
     */
    public void handleCorrectionDiagnostic(
            @Nullable Check check,
            String fallbackName,
            String verbose
    ) {
        if (correctionDiagnosticsMode == CorrectionDiagnosticMode.OFF) {
            return;
        }

        String sourceName = fallbackName == null || fallbackName.isBlank()
                ? "MovementCorrection"
                : fallbackName;

        if (check != null && check.getDisplayName() != null) {
            sourceName = check.getDisplayName();
        }

        long now = System.currentTimeMillis();
        String cooldownKey = sourceName.toLowerCase(Locale.ROOT);

        if (!claimCorrectionDiagnosticCooldown(cooldownKey, now)) {
            return;
        }

        int checkVl = 0;
        int groupVl = 0;

        if (check != null) {
            for (PunishGroup group : groups) {
                if (!group.checks.contains(check)) {
                    continue;
                }

                checkVl = Math.max(checkVl, getViolations(group, check));
                groupVl = Math.max(groupVl, group.violations.size());
            }

            if (checkVl == 0) {
                checkVl = Math.max(0, (int) Math.floor(check.getViolations()));
            }
        }

        String description = check == null || check.getDescription() == null
                ? "Movement correction diagnostic"
                : check.getDescription();

        String stableKey = check == null || check.getStableKey() == null
                ? "sparkgrim.correction.unknown"
                : check.getStableKey();

        String component = check == null
                ? "Movement"
                : componentFor(check);

        String correctionVerbose = "type=correction "
                + (verbose == null ? "" : verbose);

        int suppressed = 0;

        if (correctionDiagnosticsMode == CorrectionDiagnosticMode.NORMAL) {
            ac.grim.grimac.manager.integrity.AlertAggregationManager.Decision
                    decision =
                    GrimAPI.INSTANCE
                            .getAlertAggregationManager()
                            .evaluate(
                                    player.uuid,
                                    stableKey + ".correction",
                                    component,
                                    checkVl
                            );

            if (!decision.send()) {
                return;
            }

            suppressed = decision.suppressedSinceLastAlert();
        }

        String rendered = replaceAlertPlaceholders(
                correctionDiagnosticsFormat,
                checkVl,
                groupVl,
                sourceName,
                check != null && check.isExperimental(),
                description,
                stableKey,
                component,
                correctionVerbose,
                suppressed
        );

        Component message = MessageUtil.miniMessage(rendered);

        if (correctionDiagnosticsMode == CorrectionDiagnosticMode.VERBOSE) {
            GrimAPI.INSTANCE.getAlertManager().sendVerbose(message, null);
        } else if (testMode) {
            player.sendMessage(message);
        } else {
            GrimAPI.INSTANCE.getAlertManager().sendAlert(message, null);
        }
    }

    public boolean handleAlert(
            GrimPlayer player,
            String verbose,
            Check check
    ) {
        String value =
                verbose == null ? "" : verbose;

        return handleAlert(
                player,
                () -> value,
                check
        );
    }

    public boolean handleAlert(
            GrimPlayer player,
            Supplier<String> verbose,
            Check check
    ) {
        boolean sentDebug = false;

        for (PunishGroup group : groups) {
            if (!group.checks.contains(check)) {
                continue;
            }

            final int checkVl =
                    getViolations(group, check);

            final int groupVl =
                    group.violations.size();

            final int triggerCount =
                    group.thresholdScope == ThresholdScope.CHECK
                            ? checkVl
                            : groupVl;

            for (ParsedCommand command : group.commands) {
                @Nullable Set<@Nullable PlatformPlayer> verboseListeners =
                        null;

                if (command.command.equals("[alert]")
                        && GrimAPI.INSTANCE
                        .getAlertManager()
                        .hasVerboseListeners()) {

                    sentDebug = true;

                    String verboseForListeners =
                            safeGet(verbose);

                    String listenerCmd =
                            replaceAlertPlaceholders(
                                    "[verbose]",
                                    checkVl,
                                    groupVl,
                                    check,
                                    verboseForListeners
                            );

                    verboseListeners =
                            GrimAPI.INSTANCE
                                    .getAlertManager()
                                    .sendVerbose(
                                            MessageUtil.miniMessage(
                                                    listenerCmd
                                            ),
                                            null
                                    );
                }

                if (triggerCount < command.threshold) {
                    command.rearm(
                            group.thresholdScope,
                            check
                    );
                    continue;
                }

                boolean shouldRun =
                        command.shouldRun(
                                group.thresholdScope,
                                check,
                                triggerCount
                        );

                if (!shouldRun) {
                    continue;
                }

                if (!conditionPasses(
                        command,
                        group,
                        check,
                        checkVl,
                        groupVl
                )) {
                    continue;
                }

                command.markRun(
                        group.thresholdScope,
                        check,
                        triggerCount
                );

                ac.grim.grimac.manager.integrity
                        .AlertAggregationManager.Decision alertDecision =
                        null;

                if (command.command.equals("[alert]")) {
                    alertDecision =
                            GrimAPI.INSTANCE
                                    .getAlertAggregationManager()
                                    .evaluate(
                                            player.uuid,
                                            check.getStableKey(),
                                            componentFor(check),
                                            checkVl
                                    );

                    if (!alertDecision.send()) {
                        continue;
                    }
                }

                String renderedVerbose =
                        safeGet(verbose);

                int suppressed =
                        alertDecision == null
                                ? 0
                                : alertDecision
                                .suppressedSinceLastAlert();

                String cmd =
                        replaceAlertPlaceholders(
                                command.command,
                                checkVl,
                                groupVl,
                                check,
                                renderedVerbose,
                                suppressed
                        );

                boolean canceled =
                        COMMAND_CHANNEL.fire(
                                player,
                                check,
                                renderedVerbose,
                                cmd
                        );

                if (canceled) {
                    continue;
                }

                switch (command.command) {
                    case "[webhook]" ->
                            GrimAPI.INSTANCE
                                    .getDiscordManager()
                                    .sendAlert(
                                            player,
                                            renderedVerbose,
                                            check.getDisplayName(),
                                            checkVl
                                    );

                    case "[log]" -> {
                        if (!check.isLastFlagStoredBinaryVerbose()) {
                            String verboseWithoutGl =
                                    renderedVerbose.replaceAll(
                                            " /gl .*",
                                            ""
                                    );

                            GrimAPI.INSTANCE
                                    .getDataStoreLifecycle()
                                    .liveWriteHooks()
                                    .recordFlagFromCheck(
                                            player,
                                            check,
                                            checkVl,
                                            verboseWithoutGl
                                    );
                        }
                    }

                    case "[proxy]" ->
                            ProxyAlertMessenger
                                    .sendPluginMessage(cmd);

                    case "[alert]" -> {
                        sentDebug = true;

                        Component message =
                                MessageUtil.miniMessage(cmd);

                        if (testMode) {
                            if (verboseListeners == null
                                    || verboseListeners.contains(
                                    player.platformPlayer
                            )) {
                                player.sendMessage(message);
                            }
                        } else {
                            GrimAPI.INSTANCE
                                    .getAlertManager()
                                    .sendAlert(
                                            message,
                                            verboseListeners
                                    );
                        }
                    }

                    default ->
                            GrimAPI.INSTANCE
                                    .getScheduler()
                                    .getGlobalRegionScheduler()
                                    .run(
                                            GrimAPI.INSTANCE
                                                    .getGrimPlugin(),
                                            () -> GrimAPI.INSTANCE
                                                    .getPlatformServer()
                                                    .dispatchCommand(
                                                            GrimAPI.INSTANCE
                                                                    .getPlatformServer()
                                                                    .getConsoleSender(),
                                                            cmd
                                                    )
                                    );
                }
            }
        }

        return sentDebug;
    }

    private boolean conditionPasses(
            ParsedCommand command,
            PunishGroup group,
            Check check,
            int checkVl,
            int groupVl
    ) {
        if (command.condition == null) {
            return true;
        }

        boolean result =
                command.condition.test(
                        variable -> conditionValue(
                                variable,
                                group,
                                check,
                                checkVl,
                                groupVl
                        )
                );

        return command.unless
                ? !result
                : result;
    }

    private double conditionValue(
            String variable,
            PunishGroup group,
            Check check,
            int checkVl,
            int groupVl
    ) {
        return switch (variable) {
            case "ping", "tx_ping" ->
                    player.getTransactionPing();

            case "tps" ->
                    GrimAPI.INSTANCE
                            .getPlatformServer()
                            .getTPS();

            case "time_online" ->
                    Math.max(
                            0L,
                            System.currentTimeMillis()
                                    - player.joinTime
                    );

            case "vl", "check_vl" ->
                    checkVl;

            case "group_vl" ->
                    groupVl;

            case "sanction_vl", "sanction_check_vl" ->
                    group.sanctionViolations.count(check);

            case "sanction_group_vl" ->
                    group.sanctionViolations.size();

            case "confirmed_episodes_10m" -> {
                String family = SanctionEpisodeManager.familyFor(
                        check.getStableKey(),
                        componentFor(check)
                );
                yield GrimAPI.INSTANCE.getSanctionEpisodeManager()
                        .countWithin(
                                player.uuid,
                                family,
                                TimeUnit.MINUTES.toMillis(10L)
                        );
            }

            case "confirmed_episodes_2h" -> {
                String family = SanctionEpisodeManager.familyFor(
                        check.getStableKey(),
                        componentFor(check)
                );
                yield GrimAPI.INSTANCE.getSanctionEpisodeManager()
                        .countWithin(
                                player.uuid,
                                family,
                                TimeUnit.HOURS.toMillis(2L)
                        );
            }

            case "confirmed_episodes",
                 "confirmed_episodes_retention" -> {
                String family = SanctionEpisodeManager.familyFor(
                        check.getStableKey(),
                        componentFor(check)
                );
                yield GrimAPI.INSTANCE.getSanctionEpisodeManager()
                        .countRetention(player.uuid, family);
            }

            case "episode_confidence" -> {
                String family = SanctionEpisodeManager.familyFor(
                        check.getStableKey(),
                        componentFor(check)
                );
                yield GrimAPI.INSTANCE.getSanctionEpisodeManager()
                        .latestConfidence(player.uuid, family);
            }

            case "episode_recovery" ->
                    GrimAPI.INSTANCE.getSanctionEpisodeManager()
                            .isRecoveryBlocked(player.uuid)
                            ? 1.0D
                            : 0.0D;

            case "integrity", "integrity_score" ->
                    GrimAPI.INSTANCE
                            .getIntegrityCorrelationManager()
                            .getScore(player.uuid);

            case "short_integrity" ->
                    GrimAPI.INSTANCE
                            .getIntegrityCorrelationManager()
                            .getShortScore(player.uuid);

            case "long_integrity" ->
                    GrimAPI.INSTANCE
                            .getIntegrityCorrelationManager()
                            .getLongScore(player.uuid);

            case "confidence" ->
                    GrimAPI.INSTANCE
                            .getLagProtectionManager()
                            .heuristicConfidence(player);

            case "server_confidence" ->
                    GrimAPI.INSTANCE
                            .getLagProtectionManager()
                            .heuristicConfidence();

            case "player_confidence", "network_confidence" ->
                    GrimAPI.INSTANCE
                            .getLagProtectionManager()
                            .playerNetworkConfidence(player);

            case "jitter" ->
                    GrimAPI.INSTANCE
                            .getLagProtectionManager()
                            .getPlayerJitterMillis(player.uuid);

            case "avg_ping" ->
                    GrimAPI.INSTANCE
                            .getLagProtectionManager()
                            .getAveragePingMillis(player.uuid);

            case "severity" ->
                    GrimAPI.INSTANCE
                            .getAlertAggregationManager()
                            .severity(player.uuid)
                            .ordinal() + 1;

            case "uptime", "uptime_ticks" ->
                    GrimAPI.INSTANCE
                            .getTickManager()
                            .currentTick;

            default ->
                    dynamicConditionValue(
                            variable,
                            group
                    );
        };
    }

    private double dynamicConditionValue(
            String variable,
            PunishGroup group
    ) {
        if (variable.startsWith("perm:")) {
            String permission =
                    variable.substring("perm:".length());

            return player.hasPermission(permission)
                    ? 1.0D
                    : 0.0D;
        }

        if (variable.startsWith("chance:")) {
            String raw =
                    variable.substring("chance:".length());

            if (raw.endsWith("%")) {
                raw =
                        raw.substring(
                                0,
                                raw.length() - 1
                        );
            }

            try {
                double percent =
                        Double.parseDouble(raw);

                if (percent <= 0.0D) {
                    return 0.0D;
                }

                if (percent >= 100.0D) {
                    return 1.0D;
                }

                return ThreadLocalRandom
                        .current()
                        .nextDouble(100.0D) < percent
                        ? 1.0D
                        : 0.0D;

            } catch (NumberFormatException ignored) {
                return Double.NaN;
            }
        }

        if (variable.startsWith("vl:")) {
            String requested =
                    variable.substring("vl:".length())
                            .trim();

            int bestPriority = 0;
            double count = 0.0D;

            for (AbstractCheck candidate : group.checks) {
                int priority =
                        PunishmentSelectorPolicy.exactMatchPriority(
                                requested,
                                candidate.getCheckName(),
                                candidate.getAlternativeName(),
                                candidate.getStableKey(),
                                canonicalCheckNames
                        );

                if (priority > bestPriority) {
                    bestPriority = priority;
                    count = group.violations.count(candidate);
                } else if (priority != 0
                        && priority == bestPriority) {
                    count += group.violations.count(candidate);
                }
            }

            return count;
        }

        return Double.NaN;
    }

    private List<AbstractCheck> selectConfiguredChecks(
            String selector
    ) {
        int bestPriority = 0;
        List<AbstractCheck> matched = new ArrayList<>();

        for (AbstractCheck check : player.getChecks()) {
            int priority =
                    PunishmentSelectorPolicy.matchPriority(
                            selector,
                            check.getCheckName(),
                            check.getAlternativeName(),
                            check.getStableKey(),
                            canonicalCheckNames
                    );

            if (priority > bestPriority) {
                bestPriority = priority;
                matched.clear();
                matched.add(check);
            } else if (priority != 0
                    && priority == bestPriority) {
                matched.add(check);
            }
        }

        return matched;
    }

    private String lagState() {
        boolean server = GrimAPI.INSTANCE
                .getLagProtectionManager()
                .heuristicConfidence() < 1.0D;
        boolean playerLag = GrimAPI.INSTANCE
                .getLagProtectionManager()
                .playerNetworkConfidence(player) < 1.0D;

        if (server && playerLag) return "SERVER+PLAYER";
        if (server) return "SERVER";
        if (playerLag) return "PLAYER";
        return "OK";
    }

    private static String formatMetric(double value) {
        if (!Double.isFinite(value)) return "0";
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private boolean sanctionEvidenceEligible(Check check) {
        String key = check.getStableKey();
        if (key == null || key.isBlank()) {
            return false;
        }

        return switch (key) {
            case "grim.timer.timer" -> true;
            case "grim.velocity.anti_knockback" ->
                    "velocity.normal".equals(check.getLastComponent())
                            && sensitiveLegacyEvidenceAllowed();
            case "grim.combat.reach", "grim.combat.hitboxes" ->
                    sensitiveLegacyEvidenceAllowed();
            default -> key.startsWith("grim.crash.");
        };
    }

    private boolean sensitiveLegacyEvidenceAllowed() {
        if (!legacyOneEightNativeSensitiveSanctionsOnly) {
            return true;
        }

        ServerVersion serverVersion =
                PacketEvents.getAPI().getServerManager().getVersion();

        if (serverVersion.isNewerThan(ServerVersion.V_1_8_8)) {
            return true;
        }

        // 1.8.8 and 1.8.9 share protocol 47 and are represented as V_1_8.
        // ViaVersion/ViaRewind clients remain fully detected/prevented, but
        // translated combat geometry/movement cannot create autonomous sanctions.
        return player.getClientVersion() == ClientVersion.V_1_8;
    }

    private boolean sanctionableNow() {
        var lag = GrimAPI.INSTANCE.getLagProtectionManager();
        double serverConfidence = lag.heuristicConfidence();
        double playerConfidence = lag.playerNetworkConfidence(player);
        double confidence = Math.min(serverConfidence, playerConfidence);
        double tps = GrimAPI.INSTANCE.getPlatformServer().getTPS();

        return confidence >= sanctionMinimumConfidence
                && serverConfidence >= sanctionMinimumServerConfidence
                && playerConfidence >= sanctionMinimumPlayerConfidence
                && tps >= sanctionMinimumTps;
    }

    private static double clamp01(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }

    private static boolean defaultsToPerCheckScope(
            List<AbstractCheck> checks
    ) {
        for (AbstractCheck check : checks) {
            String key = check.getStableKey();
            if ("grim.movement.noslow".equals(key)
                    || "grim.velocity.anti_knockback".equals(key)
                    || "grim.prediction.simulation".equals(key)) {
                return true;
            }
        }
        return false;
    }

    private boolean claimCorrectionDiagnosticCooldown(
            String key,
            long now
    ) {
        final boolean[] claimed = {false};

        correctionDiagnosticsCooldowns.compute(
                key,
                (ignored, last) -> {
                    if (last == null
                            || now - last
                            >= correctionDiagnosticsCooldownMillis) {
                        claimed[0] = true;
                        return now;
                    }
                    return last;
                }
        );

        return claimed[0];
    }

    private static String componentFor(Check check) {
        String explicit = check.getLastComponent();
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }

        String key =
                check.getStableKey();

        if (key == null) {
            return "Other";
        }

        if (key.startsWith("grim.prediction.")
                || key.startsWith("grim.groundspoof.")) {
            return "Prediction";
        }

        if (key.startsWith("grim.timer.")) {
            return "Connection";
        }

        if (key.startsWith("grim.interaction.")) {
            return "Interaction";
        }

        if (key.startsWith("grim.combat.")) {
            return "Combat";
        }

        if (key.startsWith("grim.scaffolding.")
                || key.startsWith("grim.exploit.")) {
            return "World";
        }

        if (key.startsWith("grim.packetorder.")
                || key.startsWith("grim.multiactions.")
                || key.startsWith("grim.badpackets.")
                || key.startsWith("grim.crash.")) {
            return "Protocol";
        }

        if (key.startsWith("grim.velocity.")) {
            return "Velocity";
        }

        if (key.startsWith("grim.movement.")
                || key.startsWith("grim.elytra.")
                || key.startsWith("grim.sprint.")
                || key.startsWith("grim.vehicle.")) {
            return "Movement";
        }

        if (key.startsWith("grim.breaking.")) {
            return "World";
        }

        return "Other";
    }

    private static String safeGet(
            Supplier<String> supplier
    ) {
        try {
            String value =
                    supplier.get();

            return value == null
                    ? ""
                    : value;

        } catch (RuntimeException ignored) {
            return "";
        }
    }

    public void handleViolation(Check check) {
        long currentTime =
                TimeUnit.NANOSECONDS.toMillis(
                        System.nanoTime()
                );
        boolean sanctionable = sanctionableNow()
                && sanctionEvidenceEligible(check);

        for (PunishGroup group : groups) {
            if (!group.checks.contains(check)) {
                continue;
            }

            group.violations.record(
                    currentTime,
                    check,
                    group.removeViolationsAfter
            );

            if (sanctionable) {
                group.sanctionViolations.record(
                        currentTime,
                        check,
                        group.removeViolationsAfter
                );
            }
        }
    }

    private int getViolations(
            PunishGroup group,
            Check check
    ) {
        return group.violations.count(check);
    }

    private static List<String> stringList(Object value) {
        List<String> result =
                new ArrayList<>();

        if (!(value instanceof List<?> list)) {
            return result;
        }

        for (Object entry : list) {
            if (entry != null) {
                result.add(entry.toString());
            }
        }

        return result;
    }

    private static int numberOrDefault(
            Object value,
            int defaultValue
    ) {
        return value instanceof Number number
                ? number.intValue()
                : defaultValue;
    }
}

final class PunishGroup {
    final List<AbstractCheck> checks;
    final List<ParsedCommand> commands;
    final ViolationHistory<AbstractCheck> violations =
            new ViolationHistory<>();
    final ViolationHistory<AbstractCheck> sanctionViolations =
            new ViolationHistory<>();
    final int removeViolationsAfter;
    final ThresholdScope thresholdScope;

    PunishGroup(
            List<AbstractCheck> checks,
            List<ParsedCommand> commands,
            int removeViolationsAfter,
            ThresholdScope thresholdScope
    ) {
        this.checks = checks;
        this.commands = commands;
        this.removeViolationsAfter = removeViolationsAfter;
        this.thresholdScope = thresholdScope;
    }
}

enum ThresholdScope {
    GROUP,
    CHECK;

    static ThresholdScope parse(Object raw) {
        if (raw == null) {
            return GROUP;
        }

        String value =
                raw.toString()
                        .trim()
                        .toLowerCase(Locale.ROOT);

        return value.equals("check")
                ? CHECK
                : GROUP;
    }
}

final class ParsedCommand {
    final int threshold;
    final int interval;
    final String command;
    final PunishmentCondition condition;
    final boolean unless;

    private int groupExecuteCount;
    private int groupNextBoundary;

    private final IdentityHashMap<AbstractCheck, Integer>
            checkExecuteCounts =
            new IdentityHashMap<>();

    private final IdentityHashMap<AbstractCheck, Integer>
            checkNextBoundaries =
            new IdentityHashMap<>();

    ParsedCommand(
            int threshold,
            int interval,
            String command,
            PunishmentCondition condition,
            boolean unless
    ) {
        this.threshold = threshold;
        this.interval = interval;
        this.command = command;
        this.condition = condition;
        this.unless = unless;
        this.groupNextBoundary = threshold;
    }

    boolean shouldRun(
            ThresholdScope scope,
            AbstractCheck check,
            int violationCount
    ) {
        if (interval == 0) {
            return executeCount(scope, check) == 0;
        }

        return violationCount >= nextBoundary(scope, check);
    }

    void markRun(
            ThresholdScope scope,
            AbstractCheck check,
            int violationCount
    ) {
        if (interval == 0) {
            setExecuteCount(
                    scope,
                    check,
                    executeCount(scope, check) + 1
            );
            return;
        }

        int boundary =
                nextBoundary(scope, check);

        boundary +=
                ((violationCount - boundary) / interval + 1)
                        * interval;

        setNextBoundary(
                scope,
                check,
                boundary
        );
    }

    void rearm(
            ThresholdScope scope,
            AbstractCheck check
    ) {
        setNextBoundary(
                scope,
                check,
                threshold
        );

        if (interval == 0) {
            setExecuteCount(
                    scope,
                    check,
                    0
            );
        }
    }

    private int executeCount(
            ThresholdScope scope,
            AbstractCheck check
    ) {
        if (scope == ThresholdScope.GROUP) {
            return groupExecuteCount;
        }

        return checkExecuteCounts
                .getOrDefault(check, 0);
    }

    private void setExecuteCount(
            ThresholdScope scope,
            AbstractCheck check,
            int value
    ) {
        if (scope == ThresholdScope.GROUP) {
            groupExecuteCount = value;
        } else {
            checkExecuteCounts.put(check, value);
        }
    }

    private int nextBoundary(
            ThresholdScope scope,
            AbstractCheck check
    ) {
        if (scope == ThresholdScope.GROUP) {
            return groupNextBoundary;
        }

        return checkNextBoundaries
                .getOrDefault(check, threshold);
    }

    private void setNextBoundary(
            ThresholdScope scope,
            AbstractCheck check,
            int value
    ) {
        if (scope == ThresholdScope.GROUP) {
            groupNextBoundary = value;
        } else {
            checkNextBoundaries.put(check, value);
        }
    }
}
