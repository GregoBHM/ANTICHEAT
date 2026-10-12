package ac.grim.grimac.checks.impl.integrity;

import ac.grim.grimac.utils.item.ItemUseSessionTracker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsumeTimingIdentityTest {

    @Test
    void canonicalNamespaceAndCaseNormalizeToSameItem() {
        assertTrue(ItemUseSessionTracker.itemKeysMatch(
                "minecraft:golden_apple",
                "GOLDEN_APPLE"
        ));
    }

    @Test
    void suffixRelatedMaterialsDoNotAliasAsTheSameConsumeItem() {
        assertFalse(ItemUseSessionTracker.itemKeysMatch(
                "golden_apple",
                "apple"
        ));
        assertFalse(ItemUseSessionTracker.itemKeysMatch(
                "enchanted_golden_apple",
                "golden_apple"
        ));
    }
}
