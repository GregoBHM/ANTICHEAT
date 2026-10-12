package ac.grim.grimac.manager;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaffAlertResourceAuditTest {

    @Test
    void everyMessageLocaleUsesCanonicalAlertWrappers() throws IOException {
        Path dir = Path.of("src/main/resources/messages");
        try (Stream<Path> files = Files.list(dir)) {
            for (Path path : files.filter(p -> p.toString().endsWith(".yml")).toList()) {
                String text = Files.readString(path);
                assertTrue(text.contains("config-version: 4"), path.toString());
                assertTrue(text.contains("verbose-format: \"[alert] &7%verbose%\""), path.toString());
                assertTrue(text.contains("alerts-format-proxy: \"&8[proxy] [alert]\""), path.toString());
                String alertFormat = text.lines()
                        .filter(line -> line.startsWith("alerts-format:"))
                        .findFirst()
                        .orElse("");
                assertTrue(
                        alertFormat.contains("%component%"),
                        path + " alerts-format must render %component%"
                );
            }
        }
    }

    @Test
    void everyMainConfigUsesItemUsePolicyV10() throws IOException {
        Path dir = Path.of("src/main/resources/config");
        try (Stream<Path> files = Files.list(dir)) {
            for (Path path : files.filter(p -> p.toString().endsWith(".yml")).toList()) {
                String text = Files.readString(path);
                assertTrue(text.contains("config-version: 30"), path.toString());
                assertTrue(text.contains("correction-diagnostics:"), path.toString());
                assertTrue(text.contains("mode: verbose"), path.toString());
                int correctionStart = text.indexOf("correction-diagnostics:");
                int correctionEnd = text.indexOf("\nfall-integrity:", correctionStart);
                String correctionBlock = text.substring(
                        correctionStart,
                        correctionEnd < 0 ? text.length() : correctionEnd
                );
                assertFalse(
                        correctionBlock.contains("\n    enabled:"),
                        path + " should not publish deprecated correction-diagnostics.enabled"
                );
                assertTrue(text.contains("format: \"&8[correction] &7%check_name% &8[%component%] &f%verbose%\""), path.toString());
                assertTrue(text.contains("component-buckets: false"), path.toString());
                assertTrue(text.contains("session-end-grace-ms: 500"), path.toString());
                assertTrue(text.contains("context-policy:"), path.toString());
                assertTrue(text.contains("special-environment:"), path.toString());
                assertTrue(text.contains("flag-episode-window-ms: 1500"), path.toString());
                assertTrue(text.contains("evidence-episodes:"), path.toString());
                assertTrue(text.contains("max-total-flags: 2"), path.toString());
                assertFalse(text.contains("recent-check-window-ms:"), path.toString());
            }
        }
    }

    @Test
    void sensitivePunishmentGroupsUsePerCheckThresholds() throws IOException {
        Path dir = Path.of("src/main/resources/punishments");
        try (Stream<Path> files = Files.list(dir)) {
            for (Path path : files.filter(p -> p.toString().endsWith(".yml")).toList()) {
                String text = Files.readString(path);
                Map<?, ?> root = (Map<?, ?>) new Yaml().load(text);
                Object rawPunishments = root.get("Punishments");
                assertTrue(rawPunishments instanceof Map<?, ?>, path.toString());
                Map<?, ?> groups = (Map<?, ?>) rawPunishments;
                assertGroupUsesCheckScope(groups, "Simulation", path);
                assertGroupUsesCheckScope(groups, "Knockback", path);
                assertGroupUsesCheckScope(groups, "Misc", path);
                assertTrue(text.contains("stable:grim.prediction.simulation"), path.toString());
                assertTrue(text.contains("stable:grim.velocity.anti_knockback"), path.toString());
                assertTrue(text.contains("stable:grim.movement.noslow"), path.toString());
                assertTrue(text.contains("stable:grim.interaction.fast_consume"), path.toString());
            }
        }
    }


    @Test
    void punishmentLocaleLogicMatchesEnglishDefaults() throws IOException {
        Path dir = Path.of("src/main/resources/punishments");
        String english = normalizedLogic(
                Files.readString(dir.resolve("en.yml"))
        );

        try (Stream<Path> files = Files.list(dir)) {
            for (Path path : files.filter(p -> p.toString().endsWith(".yml")).toList()) {
                assertTrue(
                        normalizedLogic(Files.readString(path)).equals(english),
                        path + " punishment logic differs from en.yml"
                );
            }
        }
    }

    private static String normalizedLogic(String text) {
        return text.lines()
                .filter(line -> !line.trim().isEmpty())
                .filter(line -> !line.trim().startsWith("#"))
                .reduce("", (left, right) -> left + right + "\n");
    }

    private static void assertGroupUsesCheckScope(
            Map<?, ?> groups,
            String group,
            Path path
    ) {
        Object rawGroup = groups.get(group);
        assertTrue(rawGroup instanceof Map<?, ?>, path + " missing " + group);
        Map<?, ?> groupMap = (Map<?, ?>) rawGroup;
        assertTrue(
                "check".equals(String.valueOf(groupMap.get("threshold-scope"))),
                path + " " + group
        );
    }
}
