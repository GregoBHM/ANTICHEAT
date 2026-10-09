package ac.grim.grimac.checks.impl.prediction;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhaseProtectionPolicyTest {

    @Test
    void explicitProtectedMaterialCanUseImmediateSetback() {
        assertTrue(PhaseProtectionPolicy.shouldImmediateSetback(
                true, true, true
        ));
    }

    @Test
    void dynamicProtectedTagDoesNotReceiveImmediateSetbackByItself() {
        assertFalse(PhaseProtectionPolicy.shouldImmediateSetback(
                true, true, false
        ));
    }

    @Test
    void disabledProtectionNeverImmediateSetbacks() {
        assertFalse(PhaseProtectionPolicy.shouldImmediateSetback(
                false, true, true
        ));
    }
}
