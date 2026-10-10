package ac.grim.grimac.utils.item;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyItemUseResetPolicyTest {

    @Test
    void repeatedLegacyUseKeepsSameActiveSession() {
        assertTrue(LegacyItemUseResetPolicy.preserveRepeatedUse(true, true, true));
        assertFalse(LegacyItemUseResetPolicy.preserveRepeatedUse(false, true, true));
        assertFalse(LegacyItemUseResetPolicy.preserveRepeatedUse(true, false, true));
        assertFalse(LegacyItemUseResetPolicy.preserveRepeatedUse(true, true, false));
    }

    @Test
    void legacyInventoryRefreshOnlyPreservesSameActiveItem() {
        assertTrue(LegacyItemUseResetPolicy.preserveInventoryRefresh(true, true, true, true));
        assertFalse(LegacyItemUseResetPolicy.preserveInventoryRefresh(true, true, true, false));
        assertFalse(LegacyItemUseResetPolicy.preserveInventoryRefresh(true, false, true, true));
        assertFalse(LegacyItemUseResetPolicy.preserveInventoryRefresh(true, true, false, true));
        assertFalse(LegacyItemUseResetPolicy.preserveInventoryRefresh(false, true, true, true));
    }
}
