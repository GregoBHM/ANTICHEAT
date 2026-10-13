package ac.grim.grimac.manager;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PunishmentSelectorPolicyTest {

    private static final Set<String> CANONICAL =
            Set.of("noslow", "simulation", "antikb");

    @Test
    void stableSelectorsAreExactCaseInsensitiveAndCrossVersionSafe() {
        assertTrue(PunishmentSelectorPolicy.matchPriority(
                "STABLE:GRIM.MOVEMENT.NOSLOW",
                "NoSlow",
                "UNKNOWN",
                "grim.movement.noslow",
                CANONICAL
        ) > 0);

        assertEquals(0, PunishmentSelectorPolicy.matchPriority(
                "stable:grim.movement.noslow",
                "Simulation",
                "UNKNOWN",
                "grim.prediction.simulation",
                CANONICAL
        ));

        assertEquals(0, PunishmentSelectorPolicy.matchPriority(
                "stable:grim.movement.no",
                "NoSlow",
                "UNKNOWN",
                "grim.movement.noslow",
                CANONICAL
        ));
    }

    @Test
    void canonicalNameWinsOverAConflictingAlias() {
        int canonical = PunishmentSelectorPolicy.matchPriority(
                "Simulation",
                "Simulation",
                "UNKNOWN",
                "grim.prediction.simulation",
                CANONICAL
        );
        int alias = PunishmentSelectorPolicy.matchPriority(
                "Simulation",
                "OtherCheck",
                "Simulation",
                "grim.other",
                CANONICAL
        );

        assertTrue(canonical > 0);
        assertEquals(0, alias);
    }

    @Test
    void exactConditionSelectorsNeverUseContainsMatching() {
        assertEquals(0, PunishmentSelectorPolicy.exactMatchPriority(
                "Slow",
                "NoSlow",
                "UNKNOWN",
                "grim.movement.noslow",
                CANONICAL
        ));

        assertTrue(PunishmentSelectorPolicy.exactMatchPriority(
                "NoSlow",
                "NoSlow",
                "UNKNOWN",
                "grim.movement.noslow",
                CANONICAL
        ) > 0);
    }

    @Test
    void legacyFamilyNameMatchingStillWorksForCanonicalChecks() {
        assertTrue(PunishmentSelectorPolicy.matchPriority(
                "BadPackets",
                "BadPacketsN",
                "UNKNOWN",
                "grim.badpackets.teleport_ack",
                Set.of("badpacketsn")
        ) > 0);
    }
}
