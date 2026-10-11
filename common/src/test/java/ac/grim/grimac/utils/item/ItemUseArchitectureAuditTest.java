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
}
