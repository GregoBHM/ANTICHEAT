package ac.grim.grimac.manager.config.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemUseV10MigrationAuditTest {

    @Test
    void mainConfigPublishesVersion30AndSafeMigration() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/ac/grim/grimac/manager/config/update/GrimConfigSpecs.java"
        ));

        assertTrue(source.contains(
                "ConfigUpdater.Spec.builder("/config/", 30"
        ));
        assertTrue(source.contains(".migration(29, ctx ->"));
        assertTrue(source.contains(".migration(30, ctx ->"));
        assertTrue(source.contains(
                ""alerts.component-buckets""
        ));
        assertTrue(source.contains(
                ""environment-context.evidence-episodes.window-ms""
        ));
        assertTrue(source.contains(
                ""environment-context.evidence-episodes.max-total-flags""
        ));
    }

    @Test
    void v28LegacyDisabledMigratesToOffAndPreservesCustomValues(
            @TempDir Path tempDir
    ) throws Exception {
        Path config = tempDir.resolve("config.yml");
        Files.writeString(config, """
                config-version: 28
                config-flavor: V2
                correction-diagnostics:
                    enabled: false
                    format: "&cCUSTOM %verbose%"
                alerts:
                    component-buckets: true
                NoSlow:
                    context-policy:
                        special-minimum-offset: 0.99
                """);

        ConfigUpdater.Result result = new ConfigUpdater(
                GrimConfigSpecs.class,
                Logger.getLogger("item-use-v10-migration-test")
        ).update(config.toFile(), GrimConfigSpecs.mainConfig());

        Map<String, Object> root = yaml(config);
        Map<String, Object> correction = map(root, "correction-diagnostics");
        Map<String, Object> alerts = map(root, "alerts");
        Map<String, Object> noSlow = map(root, "NoSlow");
        Map<String, Object> context = map(noSlow, "context-policy");
        Map<String, Object> environment = map(root, "environment-context");
        Map<String, Object> episodes = map(environment, "evidence-episodes");

        assertTrue(result.migrated);
        assertEquals(30, ((Number) root.get("config-version")).intValue());
        assertEquals("off", correction.get("mode"));
        assertEquals("&cCUSTOM %verbose%", correction.get("format"));
        assertFalse(correction.containsKey("enabled"));
        assertEquals(Boolean.TRUE, alerts.get("component-buckets"));
        assertEquals(
                0.99D,
                ((Number) context.get("special-minimum-offset")).doubleValue(),
                0.000001D
        );
        assertEquals(1500, ((Number) episodes.get("window-ms")).intValue());
        assertEquals(2, ((Number) episodes.get("max-total-flags")).intValue());
    }

    @Test
    void explicitModernModeWinsLegacyEnabledDuringV29Migration(
            @TempDir Path tempDir
    ) throws Exception {
        Path config = tempDir.resolve("config.yml");
        Files.writeString(config, """
                config-version: 29
                config-flavor: V2
                correction-diagnostics:
                    enabled: false
                    mode: normal
                    format: "&dCUSTOM"
                """);

        new ConfigUpdater(
                GrimConfigSpecs.class,
                Logger.getLogger("item-use-v10-mode-precedence-test")
        ).update(config.toFile(), GrimConfigSpecs.mainConfig());

        Map<String, Object> root = yaml(config);
        Map<String, Object> correction = map(root, "correction-diagnostics");

        assertEquals("normal", correction.get("mode"));
        assertEquals("&dCUSTOM", correction.get("format"));
        assertFalse(correction.containsKey("enabled"));
    }

    @Test
    void missingV30DefaultsAreAddedWithoutOverwritingOperatorValues(
            @TempDir Path tempDir
    ) throws Exception {
        Path config = tempDir.resolve("config.yml");
        Files.writeString(config, """
                config-version: 29
                config-flavor: V2
                correction-diagnostics:
                    mode: verbose
                alerts:
                    component-buckets: true
                """);

        new ConfigUpdater(
                GrimConfigSpecs.class,
                Logger.getLogger("item-use-v10-default-test")
        ).update(config.toFile(), GrimConfigSpecs.mainConfig());

        Map<String, Object> root = yaml(config);
        Map<String, Object> alerts = map(root, "alerts");
        Map<String, Object> environment = map(root, "environment-context");
        Map<String, Object> episodes = map(environment, "evidence-episodes");

        assertEquals(Boolean.TRUE, alerts.get("component-buckets"));
        assertEquals(1500, ((Number) episodes.get("window-ms")).intValue());
        assertEquals(2, ((Number) episodes.get("max-total-flags")).intValue());
    }

    @Test
    void customMessagesAlertFormatSurvivesExistingMessageMigration(
            @TempDir Path tempDir
    ) throws Exception {
        Path messages = tempDir.resolve("messages.yml");
        Files.writeString(messages, """
                config-version: 3
                config-flavor: V2
                alerts-format: "&cCUSTOM %player% %check_name%"
                """);

        new ConfigUpdater(
                GrimConfigSpecs.class,
                Logger.getLogger("item-use-v10-message-test")
        ).update(messages.toFile(), GrimConfigSpecs.messages());

        Map<String, Object> root = yaml(messages);
        assertEquals(
                "&cCUSTOM %player% %check_name%",
                root.get("alerts-format")
        );
        assertEquals(4, ((Number) root.get("config-version")).intValue());
    }

    @Test
    void migrationPreservesCustomCorrectionFormats() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/ac/grim/grimac/manager/config/update/GrimConfigSpecs.java"
        ));

        assertTrue(source.contains("previousCorrectionFormat == null"));
        assertTrue(source.contains(
                "previousCorrectionFormat.equals(\n                            "[alert] &7%verbose%""
        ));
        assertTrue(source.contains(
                "previousCorrectionFormat.equals(\n                            "&8[correction] &7%check_name% &f%verbose%""
        ));

        assertTrue(source.contains(
                "putIfMissing(\n                            ctx,\n                            "alerts.component-buckets""
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
                "batch.put(getConfigFile("punishments.yml")"
        ));
        assertTrue(punishment.contains("PunishmentSelectorPolicy"));
        assertTrue(punishment.contains("defaultsToPerCheckScope"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> yaml(Path path) throws Exception {
        return (Map<String, Object>) new Yaml().load(Files.readString(path));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(
            Map<String, Object> parent,
            String key
    ) {
        return (Map<String, Object>) parent.get(key);
    }
}
