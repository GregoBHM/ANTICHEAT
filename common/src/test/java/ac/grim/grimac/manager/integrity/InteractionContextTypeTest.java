package ac.grim.grimac.manager.integrity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractionContextTypeTest {

    @Test
    void customInventorySuppressesInventoryFrequency() {
        assertTrue(InteractionContextType.CUSTOM_INVENTORY.suppressInventoryFrequency());
    }

    @Test
    void customItemActionSuppressesInventoryFrequency() {
        assertTrue(InteractionContextType.CUSTOM_ITEM_ACTION.suppressInventoryFrequency());
    }
}
