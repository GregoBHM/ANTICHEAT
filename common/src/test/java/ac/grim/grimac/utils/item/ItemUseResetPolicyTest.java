package ac.grim.grimac.utils.item;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemUseResetPolicyTest {

    @Test
    void sameItemInventoryRefreshNeverInterruptsPhysicalUse() {
        assertTrue(ItemUseResetPolicy.preserveInventoryRefresh(true, true, true));
        assertFalse(ItemUseResetPolicy.shouldResetServerUse(
                ItemUseResetPolicy.Reason.INVENTORY_REFRESH,
                true,
                true,
                true,
                true
        ));
        assertFalse(ItemUseResetPolicy.shouldResetServerUse(
                ItemUseResetPolicy.Reason.INVENTORY_REFRESH,
                false,
                true,
                true,
                true
        ));
    }

    @Test
    void realItemOrSlotChangesStillResetUse() {
        assertTrue(ItemUseResetPolicy.shouldResetServerUse(
                ItemUseResetPolicy.Reason.INVENTORY_REFRESH,
                true,
                true,
                true,
                false
        ));
        assertTrue(ItemUseResetPolicy.shouldResetServerUse(
                ItemUseResetPolicy.Reason.REAL_SLOT_CHANGE,
                true,
                true,
                true,
                true
        ));
        assertTrue(ItemUseResetPolicy.shouldResetServerUse(
                ItemUseResetPolicy.Reason.REAL_ITEM_CHANGE,
                true,
                true,
                true,
                true
        ));
    }

    @Test
    void attackKeepsVanillaResetSemantics() {
        assertTrue(ItemUseResetPolicy.shouldResetServerUse(
                ItemUseResetPolicy.Reason.ATTACK,
                true,
                true,
                true,
                true
        ));
        assertFalse(ItemUseResetPolicy.shouldResetServerUse(
                ItemUseResetPolicy.Reason.ATTACK,
                true,
                false,
                true,
                true
        ));
    }

    @Test
    void repeatedLegacyUseKeepsTheSamePhysicalEpisode() {
        assertFalse(ItemUseResetPolicy.shouldResetServerUse(
                ItemUseResetPolicy.Reason.REPEATED_USE,
                true,
                true,
                true,
                true
        ));
        assertTrue(ItemUseResetPolicy.shouldResetServerUse(
                ItemUseResetPolicy.Reason.REPEATED_USE,
                false,
                true,
                true,
                true
        ));
    }
}
