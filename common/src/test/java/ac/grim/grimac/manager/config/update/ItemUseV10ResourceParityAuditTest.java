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
    void bundledMainConfigsKeepPhysicalItemUseWithoutConsumeDetection() throws Exception {
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

                assertEquals(Boolean.FALSE, alerts.get("component-buckets"), path.toString());
                assertEquals("verbose", correction.get("mode"), path.toString());
                assertFalse(interaction.containsKey("consume"), path.toString());
                assertFalse(Files.readString(path).contains("fast-consume:"), path.toString());

                assertTrue(root.containsKey("movement-context-auto"), path.toString());
                assertTrue(root.containsKey("movement-enforcement"), path.toString());
                assertTrue(root.containsKey("environment-context"), path.toString());
            }
        }
    }

    @Test
    void bundledPunishmentsKeepNoSlowIndependentAndContainNoFastConsume() throws Exception {
        Path directory = Path.of("src/main/resources/punishments");

        try (Stream<Path> files = Files.list(directory)) {
            List<Path> punishments = files
                    .filter(path -> path.toString().endsWith(".yml"))
                    .toList();

            assertFalse(punishments.isEmpty());

            for (Path path : punishments) {
                Map<String, Object> root = yaml(path);
                Object rawGroups = root.get("Punishments");
                assertTrue(rawGroups instanceof Map<?, ?>, path.toString());

                boolean foundNoSlow = false;
                for (Object rawGroup : ((Map<?, ?>) rawGroups).values()) {
                    if (!(rawGroup instanceof Map<?, ?> group)) continue;
                    Object rawChecks = group.get("checks");
                    if (!(rawChecks instanceof List<?> checks)) continue;

                    boolean noSlow = checks.stream().anyMatch(value ->
                            "stable:grim.movement.noslow".equals(String.valueOf(value)));
                    boolean fastConsume = checks.stream().anyMatch(value ->
                            "stable:grim.interaction.fast_consume".equals(String.valueOf(value)));

                    assertFalse(fastConsume, path.toString());
                    if (noSlow) {
                        assertEquals("check", String.valueOf(group.get("threshold-scope")), path.toString());
                        foundNoSlow = true;
                    }
                }

                assertTrue(foundNoSlow, path.toString());
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
