package ac.grim.grimac.manager;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.AbstractCheck;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.api.config.ConfigReloadable;
import ac.grim.grimac.api.event.events.CommandExecuteEvent;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.events.packets.ProxyAlertMessenger;
import ac.grim.grimac.platform.api.player.PlatformPlayer;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.LogUtil;
import ac.grim.grimac.utils.anticheat.MessageUtil;
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

    private String experimentalSymbol = "*";
    private String alertString;
    private String verboseAlertString;
    private boolean testMode;
    private String proxyAlertString = "";

    // v21: staff-only diagnostic alerts emitted only when Grim actually sends a
    // movement correction. They never add VL and never execute punishments.
    private boolean correctionDiagnosticsEnabled = true;
    private long correctionDiagnosticsCooldownMillis = 125L;
    private long correctionDiagnosticsRecentCheckMillis = 750L;
    private String correctionDiagnosticsFormat;
    private final Map<String, Long> correctionDiagnosticsCooldowns = new java.util.HashMap<>();

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
                "%prefix% &f%player% &bfailed &f%check_name%%experimental% "
                        + "&f(x&c%vl%&f) &7%verbose% "
                        + "&8[p=%ping% tps=%tps% corr=%integrity_score%]"
        );

        correctionDiagnosticsEnabled =
                config.getBooleanElse("correction-diagnostics.enabled", true);

        correctionDiagnosticsCooldownMillis = Math.max(0L, Math.min(5000L,
                config.getLongElse("correction-diagnostics.cooldown-ms", 125L)));

        correctionDiagnosticsRecentCheckMillis = Math.max(50L, Math.min(5000L,
                config.getLongElse("correction-diagnostics.recent-check-window-ms", 750L)));

        correctionDiagnosticsFormat = config.getStringElse(
                "correction-diagnostics.format",
                "%prefix% &f%player% &bfailed &f%check_name%%experimental% "
                        + "&f(x&c%vl%&f) &7%verbose% "
                        + "&8[p=%ping% tps=%tps% corr=%integrity_score%]"
        );

        correctionDiagnosticsCooldowns.clear();

        testMode =
                config.getBooleanElse("test-mode", false);

        proxyAlertString = config.getStringElse(
                "alerts-format-proxy",
                "%prefix% &f[&cproxy&f] &f%player% &bfailed "
                        + "<hover:show_text:\"&b%check_name%%experimental%\\n"
                        + "&8Description: &f%description%\">"
                        + "&f%check_name%%experimental%</hover> "
                        + "&f(x&c%vl%&f) &7%verbose%"
        );

        try {
            groups.clear();

            for (AbstractCheck check : player.getChecks()) {
                check.setEnabled(false);
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

                ThresholdScope thresholdScope =
                        ThresholdScope.parse(map.get("threshold-scope"));

                List<ParsedCommand> parsed =
                        new ArrayList<>();

                List<AbstractCheck> checksList =
                        new ArrayList<>();

                List<AbstractCheck> excluded =
                        new ArrayList<>();

                for (String configuredCheck : checks) {
                    String command =
                            configuredCheck.toLowerCase(Locale.ROOT);

                    boolean exclude = false;

                    if (command.startsWith("!")) {
                        exclude = true;
                        command = command.substring(1);
                    }

                    for (AbstractCheck check : player.getChecks()) {
                        String checkName =
                                check.getCheckName() == null
                                        ? ""
                                        : check.getCheckName()
                                        .toLowerCase(Locale.ROOT);

                        String alternativeName =
                                check.getAlternativeName() == null
                                        ? ""
                                        : check.getAlternativeName()
                                        .toLowerCase(Locale.ROOT);

                        if (checkName.contains(command)
                                || alternativeName.contains(command)) {

                            if (exclude) {
                                if (!excluded.contains(check)) {
                                    excluded.add(check);
                                }
                            } else if (!checksList.contains(check)) {
                                checksList.add(check);
                            }
                        }
                    }

                    for (AbstractCheck check : excluded) {
                        checksList.remove(check);
                    }
                }

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
        String severity =
                GrimAPI.INSTANCE.getAlertAggregationManager()
                        .severity(player.uuid)
                        .name();

        return MessageUtil.replacePlaceholders(
                player,
                original
                        .replace("[alert]", alertString)
                        .replace("[verbose]", verboseAlertString)
                        .replace("[proxy]", proxyAlertString)
                        .replace("%check_name%", check.getDisplayName())
                        .replace(
                                "%experimental%",
                                check.isExperimental()
                                        ? experimentalSymbol
                                        : ""
                        )
                        .replace("%vl%", Integer.toString(vl))
                        .replace("%check_vl%", Integer.toString(vl))
                        .replace("%group_vl%", Integer.toString(groupVl))
                        .replace("%description%", check.getDescription())
                        .replace("%stable_key%", check.getStableKey())
                        .replace("%component%", componentFor(check))
                        .replace("%severity%", severity)
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
                MessageUtil.miniMessageSafe(verbose)
        );
    }

    /**
     * v21 movement-correction diagnostic. This deliberately bypasses punishment
     * thresholds and does not call handleViolation(): it is only a staff alert
     * so a real movement correction can be tied back to its most recent check.
     */
    public void handleCorrectionDiagnostic(
            @Nullable Check check,
            String fallbackName,
            String verbose
    ) {
        if (!correctionDiagnosticsEnabled) {
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
        Long last = correctionDiagnosticsCooldowns.get(cooldownKey);

        if (last != null && now - last < correctionDiagnosticsCooldownMillis) {
            return;
        }

        correctionDiagnosticsCooldowns.put(cooldownKey, now);

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

        String experimental = check != null && check.isExperimental()
                ? experimentalSymbol
                : "";

        String component = check == null
                ? "Movement"
                : componentFor(check);

        String severity = GrimAPI.INSTANCE
                .getAlertAggregationManager()
                .severity(player.uuid)
                .name();

        String rendered = MessageUtil.replacePlaceholders(
                player,
                correctionDiagnosticsFormat
                        .replace("%check_name%", sourceName)
                        .replace("%experimental%", experimental)
                        .replace("%vl%", Integer.toString(checkVl))
                        .replace("%check_vl%", Integer.toString(checkVl))
                        .replace("%group_vl%", Integer.toString(groupVl))
                        .replace("%description%", description)
                        .replace("%stable_key%", stableKey)
                        .replace("%component%", component)
                        .replace("%severity%", severity)
                        .replace("%suppressed%", "0")
                        .replace("%suppressed_suffix%", "")
        ).replace(
                "%verbose%",
                MessageUtil.miniMessageSafe(verbose == null ? "" : verbose)
        );

        Component message = MessageUtil.miniMessage(rendered);

        if (testMode) {
            player.sendMessage(message);
        } else {
            GrimAPI.INSTANCE.getAlertManager().sendAlert(message, null);
        }
    }

    public long getCorrectionDiagnosticsRecentCheckMillis() {
        return correctionDiagnosticsRecentCheckMillis;
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
                            .toLowerCase(Locale.ROOT);

            for (AbstractCheck candidate : group.checks) {
                String name =
                        candidate.getCheckName() == null
                                ? ""
                                : candidate.getCheckName()
                                .toLowerCase(Locale.ROOT);

                String alternative =
                        candidate.getAlternativeName() == null
                                ? ""
                                : candidate.getAlternativeName()
                                .toLowerCase(Locale.ROOT);

                if (name.equals(requested)
                        || alternative.equals(requested)) {
                    return group.violations.count(candidate);
                }
            }

            return 0.0D;
        }

        return Double.NaN;
    }

    private static String componentFor(Check check) {
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
                || key.startsWith("grim.multiactions.")) {
            return "Protocol";
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
        for (PunishGroup group : groups) {
            if (group.checks.contains(check)) {
                long currentTime =
                        TimeUnit.NANOSECONDS.toMillis(
                                System.nanoTime()
                        );

                group.violations.record(
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
