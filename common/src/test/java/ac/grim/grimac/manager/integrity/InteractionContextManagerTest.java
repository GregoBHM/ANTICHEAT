package ac.grim.grimac.manager.integrity;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InteractionContextManagerTest {

    @Test
    void disablingOnePluginOnlyClearsThatSourcePrefix() {
        InteractionContextManager manager = new InteractionContextManager();
        UUID uuid = UUID.randomUUID();

        manager.begin(
                uuid,
                InteractionContextType.CUSTOM_CONSUME,
                "RPGItems:FoodPower",
                1000L
        );
        manager.begin(
                uuid,
                InteractionContextType.CUSTOM_ITEM_ACTION,
                "MagicSpells:Wand",
                1000L
        );
        manager.begin(
                uuid,
                InteractionContextType.COMBATPLUS_COOLDOWN,
                "CombatPlus:Gapple",
                1000L
        );

        UUID other = UUID.randomUUID();
        manager.begin(
                other,
                InteractionContextType.CUSTOM_CONSUME,
                "RPGItems:OtherPlayer",
                1000L
        );

        manager.clearSourcePrefix(uuid, "RPGItems");

        List<InteractionContextManager.Context> active = manager.getActive(uuid);
        assertFalse(active.stream().anyMatch(
                context -> context.getSource().startsWith("RPGItems:")
        ));
        assertTrue(active.stream().anyMatch(
                context -> context.getSource().startsWith("MagicSpells:")
        ));
        assertTrue(active.stream().anyMatch(
                context -> context.getSource().startsWith("CombatPlus:")
        ));
        assertTrue(manager.getActive(other).stream().anyMatch(
                context -> context.getSource().startsWith("RPGItems:")
        ));
    }

    @Test
    void perPlayerContextCardinalityIsBounded() {
        InteractionContextManager manager = new InteractionContextManager();
        UUID uuid = UUID.randomUUID();

        for (int index = 0; index < 256; index++) {
            manager.begin(
                    uuid,
                    InteractionContextType.CUSTOM_ITEM_ACTION,
                    "Plugin:" + index,
                    15_000L
            );
        }

        assertTrue(manager.getActive(uuid).size() <= 64);
    }
}
