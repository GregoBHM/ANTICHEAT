package ac.grim.grimac.checks.impl.timer;

import ac.grim.grimac.manager.integrity.BlinkMitigationProfile;
import ac.grim.grimac.manager.integrity.ConnectionProtectionState;

final class ConnectionStallPolicy {
    private ConnectionStallPolicy() {
    }

    static boolean requiresConfirmedLegacyRelease(boolean canSkipTicks, BlinkMitigationProfile profile) {
        return !canSkipTicks && profile != BlinkMitigationProfile.LOCKDOWN;
    }

    static boolean hasConfirmedSelectiveEvidence(
            long gapNanos,
            int transactionAdvance,
            long confirmGapNanos,
            int minTransactionAdvance
    ) {
        return gapNanos >= confirmGapNanos && transactionAdvance >= minTransactionAdvance;
    }

    static boolean hasStrongPreReleaseEvidence(
            long gapNanos,
            int transactionAdvance,
            long configuredMinimumGapNanos,
            long confirmGapNanos,
            int configuredMinimumTransactions,
            int confirmMinimumTransactions,
            double serverConfidence,
            double playerConfidence,
            double minimumConfidence
    ) {
        long requiredGap = Math.max(configuredMinimumGapNanos, confirmGapNanos);
        int requiredTransactions = Math.max(configuredMinimumTransactions, confirmMinimumTransactions);

        return gapNanos >= requiredGap
                && transactionAdvance >= requiredTransactions
                && serverConfidence >= minimumConfidence
                && playerConfidence >= minimumConfidence;
    }

    static boolean shouldFinishRecovery(
            boolean movementArrived,
            long recoveryElapsedNanos,
            int movementPacketsIncludingCurrent,
            int transactionAdvance,
            long cleanRecoveryNanos,
            int minimumMovementPackets,
            int minimumTransactions,
            long maximumRecoveryNanos
    ) {
        if (recoveryElapsedNanos >= maximumRecoveryNanos) {
            return true;
        }

        return movementArrived
                && recoveryElapsedNanos >= cleanRecoveryNanos
                && movementPacketsIncludingCurrent >= minimumMovementPackets
                && transactionAdvance >= minimumTransactions;
    }

    static boolean shouldBlockQueuedActions(
            boolean strongPreReleaseEvidence,
            boolean hardReleaseEpisodeActive,
            long nowNanos,
            long hardReleaseCancelUntilNanos,
            boolean stallActive,
            boolean selectiveConfirmed,
            ConnectionProtectionState protectionState
    ) {
        if (strongPreReleaseEvidence) {
            return true;
        }

        if (hardReleaseEpisodeActive && nowNanos < hardReleaseCancelUntilNanos) {
            return true;
        }

        return stallActive
                && selectiveConfirmed
                && protectionState == ConnectionProtectionState.LOCKDOWN;
    }

    static boolean shouldFlagQueuedActions(
            boolean hardReleaseEpisodeActive,
            long nowNanos,
            long hardReleaseCancelUntilNanos,
            boolean stallActive,
            boolean selectiveConfirmed,
            ConnectionProtectionState protectionState
    ) {
        if (hardReleaseEpisodeActive && nowNanos < hardReleaseCancelUntilNanos) {
            return true;
        }

        return stallActive
                && selectiveConfirmed
                && protectionState == ConnectionProtectionState.LOCKDOWN;
    }
}
