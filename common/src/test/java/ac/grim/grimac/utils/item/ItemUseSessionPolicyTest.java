package ac.grim.grimac.utils.item;

import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemUseSessionPolicyTest {

    @Test
    void compatibleMetadataRefreshKeepsTheSamePhysicalIdentity() {
        assertTrue(ItemUseSessionTracker.samePhysicalIdentity(
                "golden_apple",
                0,
                1600L,
                "golden_apple",
                0,
                1600L
        ));
    }

    @Test
    void legacyVariantChangeCannotMasqueradeAsTheSameConsumable() {
        assertFalse(ItemUseSessionTracker.samePhysicalIdentity(
                "golden_apple",
                0,
                1600L,
                "golden_apple",
                1,
                1600L
        ));
    }

    @Test
    void changedConsumeDurationStartsANewPhysicalIdentity() {
        assertFalse(ItemUseSessionTracker.samePhysicalIdentity(
                "custom_food",
                0,
                1600L,
                "custom_food",
                0,
                800L
        ));
    }

    @Test
    void serverConfirmedUnknownTimingCanStillMatchTheRealClientUse() {
        assertTrue(ItemUseSessionTracker.samePhysicalIdentity(
                "potion",
                0,
                0L,
                "potion",
                0,
                1600L
        ));
    }

    @Test
    void staleConsumablesExpireOnlyAfterExpectedDurationPlusGrace() {
        assertFalse(ItemUseSessionTracker.isConsumableStale(
                1_000L,
                1_600L,
                5_600L,
                3_000L
        ));
        assertTrue(ItemUseSessionTracker.isConsumableStale(
                1_000L,
                1_600L,
                5_601L,
                3_000L
        ));
        assertFalse(ItemUseSessionTracker.isConsumableStale(
                1_000L,
                0L,
                60_000L,
                3_000L
        ));
    }

    @Test
    void sessionSnapshotCarriesLifecycleAndRefreshMetadata() {
        ItemUseSessionTracker.Session session =
                new ItemUseSessionTracker.Session(
                        7L,
                        InteractionHand.MAIN_HAND,
                        2,
                        "golden_apple",
                        1_000L,
                        42,
                        1_600L,
                        3,
                        4,
                        1_450L,
                        0,
                        null,
                        "combatplus_cooldown@CombatPlus:gapple"
                );

        assertTrue(session.active());
        assertEquals(3, session.refreshes());
        assertEquals(4, session.repeatedUses());
        assertEquals(1_450L, session.lastRefreshAtMillis());
        assertEquals(0, session.legacyData());

        ItemUseSessionTracker.Session ended =
                session.withEndReason(
                        ItemUseResetPolicy.Reason.STALE_TIMEOUT
                );

        assertFalse(ended.active());
        assertEquals(
                ItemUseResetPolicy.Reason.STALE_TIMEOUT,
                ended.endReason()
        );
    }
}
