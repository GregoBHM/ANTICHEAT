package ac.grim.grimac.manager.config.update;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemUseV10ResourceParityAuditTest {

    @Test
    void bundledMainConfigsPublishTheSameV10SafetyDefaults() throws Exception {
        Path directory = Path.of("src/main/resources/config");

        try (Stream<Path> files = Files.list(directory)) {
            List<Path> configs = files
                    .filter(path -> path.toString().endsWith(".yml"))
                    .toList();

            assertFalse(configs.isEmpty());

            for (Path path : configs) {
                Map<String, Object> root = yaml(path);
                Map<String, Object> alerts = map(root, "alerts");
                Map<String, Object> correction = map(root, "correction-diagnostics");
                Map<String, Object> interaction = map(root, "interaction-integrity");
                Map<String, Object> consume = map(interaction, "consume");

                assertEquals(Boolean.FALSE, alerts.get("component-buckets"), path.toString());
                assertEquals("verbose", correction.get("mode"), path.toString());
                assertEquals(Boolean.TRUE, consume.get("enabled"), path.toString());

                assertTrue(root.containsKey("movement-context-auto"), path.toString());
                assertTrue(root.containsKey("movement-enforcement"), path.toString());
                assertTrue(root.containsKey("environment-context"), path.toString());
            }
        }
    }

    @Test
    void bundledPunishmentsKeepItemUseChecksIndependent() throws Exception {
        Path directory = Path.of("src/main/resources/punishments");

        try (Stream<Path> files = Files.list(directory)) {
            List<Path> punishments = files
                    .filter(path -> path.toString().endsWith(".yml"))
                    .toList();

            assertFalse(punishments.isEmpty());

            for (Path path : punishments) {
                Map<String, Object> root = yaml(path);
                Object rawGroups = root.get("Punishments");
                assertTrue(rawGroups instanceof List<?>, path.toString());

                boolean found = false;
                for (Object rawGroup : (List<?>) rawGroups) {
                    if (!(rawGroup instanceof Map<?, ?> group)) {
                        continue;
                    }

                    Object rawChecks = group.get("checks");
                    if (!(rawChecks instanceof List<?> checks)) {
                        continue;
                    }

                    boolean noSlow = checks.stream().anyMatch(value ->
                            "stable:grim.movement.noslow".equals(String.valueOf(value)));
                    boolean fastConsume = checks.stream().anyMatch(value ->
                            "stable:grim.interaction.fast_consume".equals(String.valueOf(value)));

                    if (!noSlow && !fastConsume) {
                        continue;
                    }

                    assertTrue(noSlow, path.toString());
                    assertTrue(fastConsume, path.toString());
                    assertEquals("check", String.valueOf(group.get("threshold-scope")), path.toString());
                    found = true;
                }

                assertTrue(found, path.toString());
            }
        }
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
        Object value = parent.get(key);
        assertTrue(value instanceof Map<?, ?>, key);
        return (Map<String, Object>) value;
    }
}
