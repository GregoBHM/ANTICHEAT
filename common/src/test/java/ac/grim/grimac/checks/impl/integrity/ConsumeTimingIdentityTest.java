package ac.grim.grimac.checks.impl.integrity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsumeTimingIdentityTest {

    @Test
    void canonicalNamespaceAndCaseNormalizeToSameItem() {
        assertTrue(ConsumeTiming.itemKeysMatch(
                "minecraft:golden_apple",
                "GOLDEN_APPLE"
        ));
    }

    @Test
    void suffixRelatedMaterialsDoNotAliasAsTheSameConsumeItem() {
        assertFalse(ConsumeTiming.itemKeysMatch(
                "golden_apple",
                "apple"
        ));
        assertFalse(ConsumeTiming.itemKeysMatch(
                "enchanted_golden_apple",
                "golden_apple"
        ));
    }
}
