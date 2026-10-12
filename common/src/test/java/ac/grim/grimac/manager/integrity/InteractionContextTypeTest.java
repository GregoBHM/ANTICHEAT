package ac.grim.grimac.manager.integrity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractionContextTypeTest {

    @Test
    void combatPlusCooldownOnlySuppressesConsumeTiming() {
        assertTrue(InteractionContextType.COMBATPLUS_COOLDOWN.suppressConsumeTiming());
        assertFalse(InteractionContextType.COMBATPLUS_COOLDOWN.suppressInventoryFrequency());
    }

    @Test
    void customConsumeDoesNotBecomeAnInventoryBypass() {
        assertTrue(InteractionContextType.CUSTOM_CONSUME.suppressConsumeTiming());
        assertFalse(InteractionContextType.CUSTOM_CONSUME.suppressInventoryFrequency());
    }

    @Test
    void customInventoryDoesNotBecomeAConsumeBypass() {
        assertFalse(InteractionContextType.CUSTOM_INVENTORY.suppressConsumeTiming());
        assertTrue(InteractionContextType.CUSTOM_INVENTORY.suppressInventoryFrequency());
    }
}
