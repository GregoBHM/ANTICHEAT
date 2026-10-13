package ac.grim.grimac.utils.latency;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LatencyQueueAuditTest {

    @Test
    void transactionQueueUsesCompactDequeEntries() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/ac/grim/grimac/utils/latency/LatencyUtils.java"
        ));

        assertTrue(source.contains("ArrayDeque<TransactionTask>"));
        assertTrue(source.contains("record TransactionTask(int transactionId, Runnable task)"));
        assertFalse(source.contains("LinkedList<"));
        assertFalse(source.contains("IntToObjectPair"));
    }

    @Test
    void singleBlockUpdatesCapturePrimitiveValues() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/ac/grim/grimac/events/packets/worldreader/BasePacketWorldReader.java"
        ));

        assertTrue(source.contains("int blockId = blockChange.getBlockId()"));
        assertTrue(source.contains("updateBlock(x, y, z, blockId)"));
    }
}
