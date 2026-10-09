package ac.grim.grimac.checks.impl.timer;

import ac.grim.grimac.manager.integrity.BlinkMitigationProfile;
import ac.grim.grimac.manager.integrity.ConnectionProtectionState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConnectionStallPolicyTest {

    @Test
    void balancedLegacyRequiresNormalSelectiveConfirmation() {
        assertTrue(ConnectionStallPolicy.requiresConfirmedLegacyRelease(
                false, BlinkMitigationProfile.BALANCED));
        assertTrue(ConnectionStallPolicy.requiresConfirmedLegacyRelease(
                false, BlinkMitigationProfile.SAFE));
        assertFalse(ConnectionStallPolicy.requiresConfirmedLegacyRelease(
                false, BlinkMitigationProfile.LOCKDOWN));
        assertFalse(ConnectionStallPolicy.requiresConfirmedLegacyRelease(
                true, BlinkMitigationProfile.BALANCED));
    }

    @Test
    void eightyMillisecondsAndOneTransactionAreNotEnoughForActionFirstPrevention() {
        long ms = 1_000_000L;
        assertFalse(ConnectionStallPolicy.hasStrongPreReleaseEvidence(
                80L * ms,
                1,
                80L * ms,
                900L * ms,
                1,
                3,
                1.0D,
                1.0D,
                0.55D
        ));
        assertTrue(ConnectionStallPolicy.hasStrongPreReleaseEvidence(
                900L * ms,
                3,
                80L * ms,
                900L * ms,
                1,
                3,
                0.90D,
                0.90D,
                0.55D
        ));
    }

    @Test
    void playerNetworkConfidenceAlsoGatesPreReleasePrevention() {
        long ms = 1_000_000L;
        assertFalse(ConnectionStallPolicy.hasStrongPreReleaseEvidence(
                900L * ms,
                3,
                80L * ms,
                900L * ms,
                1,
                3,
                0.95D,
                0.40D,
                0.55D
        ));
    }

    @Test
    void recoveryNeverBlocksQueuedActions() {
        long now = 1_000L;
        assertFalse(ConnectionStallPolicy.shouldBlockQueuedActions(
                false,
                false,
                now,
                0L,
                true,
                true,
                ConnectionProtectionState.RECOVERY
        ));
    }

    @Test
    void onlyConfirmedLockdownOrActiveDiscardWindowBlocksActions() {
        long now = 1_000L;
        assertTrue(ConnectionStallPolicy.shouldBlockQueuedActions(
                false,
                false,
                now,
                0L,
                true,
                true,
                ConnectionProtectionState.LOCKDOWN
        ));
        assertTrue(ConnectionStallPolicy.shouldBlockQueuedActions(
                false,
                true,
                now,
                now + 10L,
                false,
                false,
                ConnectionProtectionState.NORMAL
        ));
        assertFalse(ConnectionStallPolicy.shouldBlockQueuedActions(
                false,
                true,
                now,
                now,
                false,
                false,
                ConnectionProtectionState.NORMAL
        ));
    }

    @Test
    void recoveryHasAbsoluteTimeoutWithoutAnotherMovementPacket() {
        long second = 1_000_000_000L;
        assertTrue(ConnectionStallPolicy.shouldFinishRecovery(
                false,
                8L * second,
                1,
                0,
                2L * second,
                8,
                2,
                8L * second
        ));
        assertFalse(ConnectionStallPolicy.shouldFinishRecovery(
                false,
                7L * second,
                20,
                20,
                2L * second,
                8,
                2,
                8L * second
        ));
    }

    @Test
    void cleanRecoveryCanFinishBeforeSafetyTimeout() {
        long second = 1_000_000_000L;
        assertTrue(ConnectionStallPolicy.shouldFinishRecovery(
                true,
                2L * second,
                8,
                2,
                2L * second,
                8,
                2,
                8L * second
        ));
    }
}
