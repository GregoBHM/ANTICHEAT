package ac.grim.grimac.manager;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PunishmentThresholdScopeTest {

    @Test
    void explicitThresholdScopeParsingIsStable() {
        assertEquals(ThresholdScope.CHECK, ThresholdScope.parse("check"));
        assertEquals(ThresholdScope.CHECK, ThresholdScope.parse("CHECK"));
        assertEquals(ThresholdScope.GROUP, ThresholdScope.parse("group"));
        assertEquals(ThresholdScope.GROUP, ThresholdScope.parse(null));
    }

    @Test
    void runtimeUsesPerCheckCountWhenScopeIsCheck() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/ac/grim/grimac/manager/PunishmentManager.java"
        ));

        assertTrue(source.contains(
                "group.thresholdScope == ThresholdScope.CHECK\n"
                        + "                            ? checkVl\n"
                        + "                            : groupVl"
        ));
    }

    @Test
    void sensitiveLegacyGroupsDefaultToCheckButExplicitScopeWins()
            throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/ac/grim/grimac/manager/PunishmentManager.java"
        ));

        assertTrue(source.contains(
                "thresholdScopeRaw == null\n"
                        + "                                && defaultsToPerCheckScope(checksList)"
        ));
        assertTrue(source.contains("\"grim.movement.noslow\".equals(key)"));
        assertTrue(source.contains(
                "\"grim.interaction.fast_consume\".equals(key)"
        ));
        assertTrue(source.contains(
                "\"grim.velocity.anti_knockback\".equals(key)"
        ));
        assertTrue(source.contains(
                "\"grim.prediction.simulation\".equals(key)"
        ));
    }
}
