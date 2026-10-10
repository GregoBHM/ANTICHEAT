package ac.grim.grimac.manager.integrity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CancelledBlockSupportPolicyTest {

    @Test
    void detectsTowerTakeoffFromCancelledBlockTop() {
        assertTrue(CancelledBlockSupportPolicy.isSupportUse(
                65.0D, 65.42D, 65.0D, true, true
        ));
    }

    @Test
    void detectsStandingOnCancelledGhostSupport() {
        assertTrue(CancelledBlockSupportPolicy.isSupportUse(
                65.0D, 65.0D, 65.0D, true, true
        ));
    }

    @Test
    void doesNotFlagPassingAboveWithoutStartingOnSupport() {
        assertFalse(CancelledBlockSupportPolicy.isSupportUse(
                65.40D, 65.82D, 65.0D, true, true
        ));
    }

    @Test
    void horizontalMissNeverCountsAsSupport() {
        assertFalse(CancelledBlockSupportPolicy.isSupportUse(
                65.0D, 65.42D, 65.0D, false, false
        ));
    }
}
