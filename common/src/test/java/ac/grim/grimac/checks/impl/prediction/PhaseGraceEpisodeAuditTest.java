package ac.grim.grimac.checks.impl.prediction;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhaseGraceEpisodeAuditTest {

    @Test
    void recentBlockGraceIsAnEpisodeNotADelayedPunishment() throws IOException {
        String source = Files.readString(
                Path.of("src/main/java/ac/grim/grimac/checks/impl/prediction/Phase.java")
        );

        assertTrue(source.contains("private Set<BlockKey> pendingGrace;"));
        assertTrue(source.contains("pendingGrace.add("));
        assertFalse(source.contains("elapsedTicks"));
        assertFalse(source.contains("Map.Entry<BlockKey, Integer>"));
        assertTrue(source.contains("processPendingGrace(newBB);"));
    }

    @Test
    void immediateProtectionUsesExplicitMaterialPolicy() throws IOException {
        String source = Files.readString(
                Path.of("src/main/java/ac/grim/grimac/checks/impl/prediction/Phase.java")
        );

        assertTrue(source.contains("PhaseProtectionPolicy.shouldImmediateSetback("));
        assertTrue(source.contains("protectedMaterials.contains(materialKey(state.getType()))"));
    }
}
