package ac.grim.grimac.manager;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PunishmentSelectorPolicyTest {

    private static final Set<String> CANONICAL =
            Set.of("noslow", "fastconsume", "simulation");

    @Test
    void canonicalNoSlowCannotSelectFastConsumeThroughLegacyAlias() {
        int noSlow = PunishmentSelectorPolicy.matchPriority(
                "NoSlow",
                "NoSlow",
                "UNKNOWN",
                "grim.movement.noslow",
                CANONICAL
        );
        int oldFastConsumeAlias = PunishmentSelectorPolicy.matchPriority(
                "NoSlow",
                "FastConsume",
                "NoSlow",
                "grim.interaction.fast_consume",
                CANONICAL
        );

        assertTrue(noSlow > 0);
        assertEquals(0, oldFastConsumeAlias);
    }

    @Test
    void stableSelectorsAreExactCaseInsensitiveAndCrossVersionSafe() {
        assertTrue(PunishmentSelectorPolicy.matchPriority(
                "STABLE:GRIM.INTERACTION.FAST_CONSUME",
                "FastConsume",
                "FastEat",
                "grim.interaction.fast_consume",
                CANONICAL
        ) > 0);

        assertEquals(0, PunishmentSelectorPolicy.matchPriority(
                "stable:grim.movement.noslow",
                "FastConsume",
                "FastEat",
                "grim.interaction.fast_consume",
                CANONICAL
        ));

        assertEquals(0, PunishmentSelectorPolicy.matchPriority(
                "stable:grim.interaction.fast",
                "FastConsume",
                "FastEat",
                "grim.interaction.fast_consume",
                CANONICAL
        ));
    }

    @Test
    void nonConflictingLegacyAliasStillWorks() {
        assertTrue(PunishmentSelectorPolicy.matchPriority(
                "FastEat",
                "FastConsume",
                "FastEat",
                "grim.interaction.fast_consume",
                CANONICAL
        ) > 0);
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
