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
    void everyMainConfigUsesCanonicalCorrectionFormat() throws IOException {
        Path dir = Path.of("src/main/resources/config");
        try (Stream<Path> files = Files.list(dir)) {
            for (Path path : files.filter(p -> p.toString().endsWith(".yml")).toList()) {
                String text = Files.readString(path);
                assertTrue(text.contains("config-version: 28"), path.toString());
                assertTrue(text.contains("format: \"[alert] &7%verbose%\""), path.toString());
                assertFalse(text.contains("recent-check-window-ms:"), path.toString());
            }
        }
    }
}
