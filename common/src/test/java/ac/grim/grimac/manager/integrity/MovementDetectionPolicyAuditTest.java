package ac.grim.grimac.manager.integrity;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MovementDetectionPolicyAuditTest {

    @Test
    void noSlowUsesPhysicalSessionAndSharedSpecialEpisode() throws IOException {
        String source = read(
                "src/main/java/ac/grim/grimac/checks/impl/movement/NoSlow.java"
        );

        assertTrue(source.contains("itemUseSessionTracker.activeSession()"));
        assertTrue(source.contains("resolveMovementEvidenceEpisode"));
        assertTrue(source.contains("hasTrustedVelocityContext"));
        assertTrue(source.contains("hasObservedVelocityContext"));

        String contextual = between(
                source,
                "private void handleContextual(",
                "private static @NotNull String componentFor("
        );
        assertFalse(contextual.contains("reward()"));
    }

    @Test
    void antiKbTreatsObservedAndTrustedVelocityDifferently() throws IOException {
        String source = read(
                "src/main/java/ac/grim/grimac/checks/impl/velocity/KnockbackHandler.java"
        );

        assertTrue(source.contains("hasTrustedVelocityContext"));
        assertTrue(source.contains("hasObservedVelocityContext"));
        assertTrue(source.contains("resolveMovementEvidenceEpisode"));

        String uncertain = between(
                source,
                "private void handleUncertainVelocityEvidence(",
                "private static @NotNull String velocityComponent("
        );
        assertFalse(uncertain.contains("reward()"));
    }

    @Test
    void simulationSuppressesOnlyTrustedVelocityIncompatibleEvidence() throws IOException {
        String simulation = read(
                "src/main/java/ac/grim/grimac/checks/impl/prediction/OffsetHandler.java"
        );
        String phase = read(
                "src/main/java/ac/grim/grimac/checks/impl/prediction/Phase.java"
        );
        String fastConsume = read(
                "src/main/java/ac/grim/grimac/checks/impl/integrity/ConsumeTiming.java"
        );
        String timer = read(
                "src/main/java/ac/grim/grimac/checks/impl/timer/Timer.java"
        );
        String reach = read(
                "src/main/java/ac/grim/grimac/checks/impl/combat/Reach.java"
        );

        assertTrue(simulation.contains("hasTrustedVelocityContext"));
        assertTrue(simulation.contains("resolveMovementEvidenceEpisode"));
        assertTrue(simulation.contains("specialEnvironmentCorrectionKey"));
        assertFalse(phase.contains("hasTrustedVelocityContext"));
        assertFalse(fastConsume.contains("hasTrustedVelocityContext"));
        assertFalse(timer.contains("hasTrustedVelocityContext"));
        assertFalse(reach.contains("hasTrustedVelocityContext"));
    }

    @Test
    void genericBukkitMovementBridgeNeverCreatesTrustedVelocity() throws IOException {
        String bridge = read(
                "../bukkit/src/main/java/ac/grim/grimac/platform/bukkit/events/MovementContextListener.java"
        );

        assertTrue(bridge.contains("beginObserved"));
        assertFalse(bridge.contains("beginTrusted"));
    }

    @Test
    void packetOrderChecksDoNotReadMovementTrustContexts() throws IOException {
        Path root = Path.of(
                "src/main/java/ac/grim/grimac/checks/impl/packetorder"
        );

        try (Stream<Path> files = Files.walk(root)) {
            for (Path path : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(path);
                assertFalse(
                        source.contains("hasTrustedVelocityContext"),
                        path.toString()
                );
            }
        }
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path));
    }

    private static String between(
            String source,
            String startMarker,
            String endMarker
    ) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start + startMarker.length());
        assertTrue(start >= 0 && end > start);
        return source.substring(start, end);
    }
}
