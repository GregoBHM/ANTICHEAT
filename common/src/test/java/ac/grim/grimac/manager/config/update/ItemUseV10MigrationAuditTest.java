package ac.grim.grimac.manager.config.update;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemUseV10MigrationAuditTest {

    @Test
    void mainConfigPublishesVersion30AndSafeMigration() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/ac/grim/grimac/manager/config/update/GrimConfigSpecs.java"
        ));

        assertTrue(source.contains(
                "ConfigUpdater.Spec.builder(\"/config/\", 30"
        ));
        assertTrue(source.contains(".migration(30, ctx ->"));
        assertTrue(source.contains(
                "\"alerts.component-buckets\""
        ));
        assertTrue(source.contains(
                "\"environment-context.evidence-episodes.window-ms\""
        ));
        assertTrue(source.contains(
                "\"environment-context.evidence-episodes.max-total-flags\""
        ));
    }

    @Test
    void migrationPreservesCustomCorrectionFormats() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/ac/grim/grimac/manager/config/update/GrimConfigSpecs.java"
        ));

        assertTrue(source.contains("previousCorrectionFormat == null"));
        assertTrue(source.contains(
                "previousCorrectionFormat.equals(\n                            \"[alert] &7%verbose%\""
        ));
        assertTrue(source.contains(
                "previousCorrectionFormat.equals(\n                            \"&8[correction] &7%check_name% &f%verbose%\""
        ));

        // Generic new v30 values use putIfMissing rather than unconditional
        // writes, so an existing operator value wins.
        assertTrue(source.contains(
                "putIfMissing(\n                            ctx,\n                            \"alerts.component-buckets\""
        ));
    }

    @Test
    void punishmentsRemainUserOwnedAndMigrateAtRuntimeBySelectorPolicy()
            throws Exception {
        String manager = Files.readString(Path.of(
                "src/main/java/ac/grim/grimac/manager/config/ConfigManagerFileImpl.java"
        ));
        String punishment = Files.readString(Path.of(
                "src/main/java/ac/grim/grimac/manager/PunishmentManager.java"
        ));

        assertTrue(manager.contains(
                "punishments.yml is intentionally absent"
        ));
        assertFalse(manager.contains(
                "batch.put(getConfigFile(\"punishments.yml\")"
        ));
        assertTrue(punishment.contains("PunishmentSelectorPolicy"));
        assertTrue(punishment.contains("defaultsToPerCheckScope"));
    }
}
