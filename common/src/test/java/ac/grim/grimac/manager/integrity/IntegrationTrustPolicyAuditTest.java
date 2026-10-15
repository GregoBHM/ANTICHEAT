package ac.grim.grimac.manager.integrity;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class IntegrationTrustPolicyAuditTest {

    @Test
    void productionTrustedVelocityProducersAreExplicitOnly() throws Exception {
        Set<String> allowedCommon = Set.of(
                "MovementContextManager.java",
                "GrimIntegrationAPI.java"
        );

        assertTokenOnlyIn(
                Path.of("src/main/java"),
                "beginTrusted(",
                allowedCommon
        );

        try (Stream<Path> files = Files.walk(Path.of("../bukkit/src/main/java"))) {
            for (Path path : files.filter(this::isJava).toList()) {
                assertFalse(
                        Files.readString(path).contains("beginTrusted("),
                        path.toString()
                );
            }
        }

        String facade = Files.readString(Path.of(
                "src/main/java/ac/grim/grimac/integration/GrimIntegrationAPI.java"
        ));
        assertTrue(facade.contains(
                "getMovementContextManager().beginTrusted("
        ));
        assertTrue(facade.contains("\"MagicSpells:\" + spellName"));
        assertTrue(facade.contains("\"RPGItems:\" + powerName"));
        assertTrue(facade.contains("\"CombatPlus:\" + mechanicName"));
    }

    @Test
    void trustedVelocityConsumersStayLimitedToCompatibleChecks()
            throws Exception {
        Set<String> allowed = Set.of(
                "MovementContextManager.java",
                "NoSlow.java",
                "KnockbackHandler.java",
                "OffsetHandler.java"
        );

        assertTokenOnlyIn(
                Path.of("src/main/java"),
                "hasTrustedVelocityContext(",
                allowed
        );
    }

    @Test
    void deterministicUnrelatedChecksDoNotReadIntegrationBypasses()
            throws Exception {
        for (String path : new String[]{
                "src/main/java/ac/grim/grimac/checks/impl/prediction/Phase.java",
                "src/main/java/ac/grim/grimac/checks/impl/combat/Reach.java",
                "src/main/java/ac/grim/grimac/checks/impl/timer/Timer.java"
        }) {
            String source = Files.readString(Path.of(path));
            assertFalse(source.contains("hasTrustedVelocityContext"), path);
            assertFalse(source.contains("getMovementContextManager()"), path);
            assertFalse(source.contains("getInteractionContextManager()"), path);
        }

        Path packetOrder = Path.of(
                "src/main/java/ac/grim/grimac/checks/impl/packetorder"
        );
        try (Stream<Path> files = Files.walk(packetOrder)) {
            for (Path path : files.filter(this::isJava).toList()) {
                String source = Files.readString(path);
                assertFalse(source.contains("MovementContext"), path.toString());
                assertFalse(source.contains("InteractionContext"), path.toString());
                assertFalse(source.contains("getMovementContextManager()"), path.toString());
                assertFalse(source.contains("getInteractionContextManager()"), path.toString());
            }
        }
    }

    @Test
    void bukkitMovementLifecycleClearsContextsOnDeathAndRespawn() throws Exception {
        String listener = Files.readString(Path.of(
                "../bukkit/src/main/java/ac/grim/grimac/platform/bukkit/events/MovementContextListener.java"
        ));

        int deathMethod = listener.indexOf("void onDeath(PlayerDeathEvent event)");
        int deathClear = listener.indexOf(
                ".clear(event.getEntity().getUniqueId())",
                deathMethod
        );
        int respawnMethod = listener.indexOf("void onRespawn(PlayerRespawnEvent event)");
        int respawnClear = listener.indexOf(
                ".clear(event.getPlayer().getUniqueId())",
                respawnMethod
        );

        assertTrue(listener.contains(
                "import org.bukkit.event.entity.PlayerDeathEvent;"
        ));
        assertTrue(listener.contains(
                "import org.bukkit.event.player.PlayerRespawnEvent;"
        ));
        assertTrue(deathMethod >= 0);
        assertTrue(deathClear > deathMethod);
        assertTrue(respawnMethod >= 0);
        assertTrue(respawnClear > respawnMethod);
    }

    @Test
    void integrationCleanupIsPlayerScopedAndPrefixAware() throws Exception {
        String facade = Files.readString(Path.of(
                "src/main/java/ac/grim/grimac/integration/GrimIntegrationAPI.java"
        ));

        assertTrue(facade.contains(
                "getMovementContextManager()\n                .clearSourcePrefix(playerId, sourcePlugin)"
        ));
        assertTrue(facade.contains(
                "getInteractionContextManager()\n                .clearSourcePrefix(playerId, sourcePlugin)"
        ));
    }

    @Test
    void antiKbUsesScopedVelocityAssessmentInsteadOfGlobalTrustedBypass()
            throws Exception {
        String knockback = Files.readString(Path.of(
                "src/main/java/ac/grim/grimac/checks/impl/velocity/KnockbackHandler.java"
        ));
        String listener = Files.readString(Path.of(
                "../bukkit/src/main/java/ac/grim/grimac/platform/bukkit/events/MovementContextListener.java"
        ));
        String facade = Files.readString(Path.of(
                "src/main/java/ac/grim/grimac/integration/GrimIntegrationAPI.java"
        ));

        assertTrue(knockback.contains(".assessVelocity("));
        assertFalse(knockback.contains(
                "getMovementContextManager().hasTrustedVelocityContext(player.uuid)"
        ));
        assertTrue(listener.contains("beginObservedVelocity("));
        assertTrue(facade.contains("beginTrustedVelocity("));
    }

    private void assertTokenOnlyIn(
            Path root,
            String token,
            Set<String> allowedNames
    ) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            for (Path path : files.filter(this::isJava).toList()) {
                String source = Files.readString(path);
                if (!source.contains(token)) continue;
                if (!allowedNames.contains(path.getFileName().toString())) {
                    fail(token + " unexpectedly appears in " + path);
                }
            }
        }
    }

    private boolean isJava(Path path) {
        return path.toString().endsWith(".java");
    }
}
