package ac.grim.grimac.checks.impl.badpackets;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BadPacketsEStageAuditTest {

    @Test
    void observesRawMovementBeforePredictionMitigationsCanCancelIt() throws IOException {
        Path checksRoot = Path.of("src/main/java/ac/grim/grimac/checks").toAbsolutePath();
        String source = Files.readString(
                checksRoot.resolve("impl/badpackets/BadPacketsE.java")
        );

        assertTrue(source.contains("implements PrePredictionPacketReceiveListener"));
        assertTrue(source.contains("onPrePredictionPacketReceive(PacketReceiveEvent event)"));
        assertFalse(source.contains("implements PacketReceiveListener"));
    }

    @Test
    void badPacketsEIsRegisteredBeforeConnectionStall() throws IOException {
        Path sourceRoot = Path.of("src/main/java/ac/grim/grimac").toAbsolutePath();
        String manager = Files.readString(sourceRoot.resolve("manager/CheckManager.java"));

        int badPackets = manager.indexOf(".put(BadPacketsE.class, new BadPacketsE(player))");
        int connectionStall = manager.indexOf(".put(ConnectionStall.class, new ConnectionStall(player))");

        assertTrue(badPackets >= 0);
        assertTrue(connectionStall >= 0);
        assertTrue(badPackets < connectionStall);
    }
}
