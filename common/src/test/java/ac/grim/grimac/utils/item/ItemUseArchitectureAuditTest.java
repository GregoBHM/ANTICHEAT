package ac.grim.grimac.utils.item;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemUseArchitectureAuditTest {

    @Test
    void commonCodeHasOnePhysicalServerResetOwner() throws IOException {
        Path root = Path.of("src/main/java");
        List<Path> directResetCallers = new ArrayList<>();

        try (Stream<Path> files = Files.walk(root)) {
            for (Path path : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(path);
                if (text.contains(".resetItemUsage(")) {
                    directResetCallers.add(path);
                }
            }
        }

        assertEquals(1, directResetCallers.size(), directResetCallers.toString());
        assertTrue(
                directResetCallers.get(0).toString().endsWith("ItemUseSessionTracker.java"),
                directResetCallers.toString()
        );
    }

    @Test
    void predictionMayFlipProtocolStateButNeverPhysicalUseSession() throws IOException {
        String prediction = Files.readString(Path.of(
                "src/main/java/ac/grim/grimac/predictionengine/predictions/PredictionEngine.java"
        ));

        assertTrue(prediction.contains("setSlowedByUsingItem"));
        assertFalse(prediction.contains("itemUseSessionTracker"));
    }

    @Test
    void fastConsumeReadsTheSharedSessionInsteadOfOwningUsePackets() throws IOException {
        String consume = Files.readString(Path.of(
                "src/main/java/ac/grim/grimac/checks/impl/integrity/ConsumeTiming.java"
        ));

        assertTrue(consume.contains("itemUseSessionTracker.sessionForConsume"));
        assertFalse(consume.contains("PacketReceiveListener"));
        assertFalse(consume.contains("PacketType.Play.Client.USE_ITEM"));
    }


    @Test
    void inventoryRefreshesUsePhysicalCompatibilityInsteadOfBareMaterialType() throws IOException {
        String replication = Files.readString(Path.of(
                "src/main/java/ac/grim/grimac/events/packets/PacketEntityReplication.java"
        ));

        assertTrue(replication.contains("ItemUseSessionTracker.isCompatibleRefresh"));
        assertTrue(replication.contains("Reason.INVENTORY_REFRESH"));
    }

    @Test
    void incomingVelocityAndMovementContextsNeverOwnPhysicalUseReset() throws IOException {
        String movementContexts = Files.readString(Path.of(
                "../bukkit/src/main/java/ac/grim/grimac/platform/bukkit/events/MovementContextListener.java"
        ));
        String knockback = Files.readString(Path.of(
                "src/main/java/ac/grim/grimac/checks/impl/velocity/KnockbackHandler.java"
        ));

        assertFalse(movementContexts.contains("requestServerReset("));
        assertFalse(movementContexts.contains(".resetItemUsage("));
        assertFalse(knockback.contains("requestServerReset("));
        assertFalse(knockback.contains(".resetItemUsage("));
    }

    @Test
    void outgoingAttackKeepsCentralVanillaResetPath() throws IOException {
        String attack = Files.readString(Path.of(
                "src/main/java/ac/grim/grimac/events/packets/PacketPlayerAttack.java"
        ));

        assertTrue(attack.contains("ItemUseResetPolicy.Reason.ATTACK"));
        assertTrue(attack.contains("itemUseSessionTracker.requestServerReset"));
        assertFalse(attack.contains(".resetItemUsage("));
    }

    @Test
    void fastConsumeStillHonorsConfiguredStaleSessionWindow() throws IOException {
        String consume = Files.readString(Path.of(
                "src/main/java/ac/grim/grimac/checks/impl/integrity/ConsumeTiming.java"
        ));

        assertTrue(consume.contains("interaction-integrity.consume.stale-extra-ms"));
        assertTrue(consume.contains("configureConsumableStaleExtraMillis"));
    }
}
