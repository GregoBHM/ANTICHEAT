package ac.grim.grimac.manager.integrity;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SprintItemUsePolicyAuditTest {
    @Test
    void uncheckedPredictionCannotContinueSprintItemUseStreak() throws IOException {
        String source = Files.readString(Path.of(
                "src/main/java/ac/grim/grimac/checks/impl/sprint/SprintC.java"
        ));
        int method = source.indexOf("void onPredictionComplete(");
        int unchecked = source.indexOf("if (!predictionComplete.isChecked())", method);
        int reset = source.indexOf("flaggedLastTick = false;", unchecked);
        int session = source.indexOf("player.itemUseSessionTracker.isActive()", method);
        int packetState = source.indexOf("player.packetStateData.isSlowedByUsingItem()", session);
        assertTrue(method >= 0 && unchecked > method);
        assertTrue(reset > unchecked && reset < session);
        assertTrue(packetState > session);
    }
}
