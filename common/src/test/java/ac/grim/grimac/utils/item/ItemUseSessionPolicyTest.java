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
                "golden_apple", 0,
                "golden_apple", 0
        ));
    }

    @Test
    void legacyVariantChangeStartsANewPhysicalIdentity() {
        assertFalse(ItemUseSessionTracker.samePhysicalIdentity(
                "golden_apple", 0,
                "golden_apple", 1
        ));
    }

    @Test
    void canonicalNamespaceAndCaseNormalizeToSameItem() {
        assertTrue(ItemUseSessionTracker.itemKeysMatch(
                "minecraft:GOLDEN_APPLE",
                "golden_apple"
        ));
    }

    @Test
    void suffixRelatedItemsNeverAlias() {
        assertFalse(ItemUseSessionTracker.itemKeysMatch(
                "golden_apple",
                "enchanted_golden_apple"
        ));
    }

    @Test
    void sessionSnapshotContainsPhysicalLifecycleOnly() {
        ItemUseSessionTracker.Session session =
                new ItemUseSessionTracker.Session(
                        7L,
                        InteractionHand.MAIN_HAND,
                        2,
                        "golden_apple",
                        1_000L,
                        42,
                        3,
                        4,
                        1_450L,
                        0,
                        null,
                        "none"
                );

        assertTrue(session.active());
        assertEquals(3, session.refreshes());
        assertEquals(4, session.repeatedUses());

        ItemUseSessionTracker.Session ended =
                session.withEndReason(ItemUseResetPolicy.Reason.CLIENT_RELEASE);

        assertFalse(ended.active());
        assertEquals(ItemUseResetPolicy.Reason.CLIENT_RELEASE, ended.endReason());
    }
}
