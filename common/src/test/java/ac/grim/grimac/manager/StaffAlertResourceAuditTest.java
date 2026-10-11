package ac.grim.grimac.manager;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
            }
        }
    }

    @Test
    void everyMainConfigUsesItemUsePolicyV10() throws IOException {
        Path dir = Path.of("src/main/resources/config");
        try (Stream<Path> files = Files.list(dir)) {
            for (Path path : files.filter(p -> p.toString().endsWith(".yml")).toList()) {
                String text = Files.readString(path);
                assertTrue(text.contains("config-version: 29"), path.toString());
                assertTrue(text.contains("correction-diagnostics:"), path.toString());
                assertTrue(text.contains("mode: verbose"), path.toString());
                assertTrue(text.contains("format: \"&8[correction] &7%check_name% &f%verbose%\""), path.toString());
                assertTrue(text.contains("session-end-grace-ms: 500"), path.toString());
                assertTrue(text.contains("context-policy:"), path.toString());
                assertTrue(text.contains("special-environment:"), path.toString());
                assertTrue(text.contains("flag-episode-window-ms: 1500"), path.toString());
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
                assertGroupUsesCheckScope(text, "Knockback", path);
                assertGroupUsesCheckScope(text, "Misc", path);
            }
        }
    }

    private static void assertGroupUsesCheckScope(String text, String group, Path path) {
        int start = text.indexOf("  " + group + ":");
        assertTrue(start >= 0, path + " missing " + group);
        int next = text.indexOf("\n  ", start + 3);
        String block = text.substring(start, next < 0 ? text.length() : next);
        assertTrue(block.contains("threshold-scope: check"), path + " " + group);
    }
}
