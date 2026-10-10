package ac.grim.grimac.manager.integrity;

import java.util.concurrent.TimeUnit;

/**
 * Final arbiter for physical movement corrections.
 *
 * This class does not decide whether a check flags. It only prevents multiple
 * independent systems from physically correcting the same movement episode.
 */
public final class MovementCorrectionCoordinator {
    private static final long POST_ACK_HOLD_NANOS = TimeUnit.MILLISECONDS.toNanos(250L);

    public enum Priority {
        ROUTINE,
        CHECK,
        AUTHORITATIVE
    }

    private long observedConnectionEpisodeId;
    private boolean connectionCorrectionApplied;
    private boolean correctionInFlight;
    private long holdUntilNanos;

    public synchronized boolean canApply(
            Priority priority,
            boolean sourceIsConnectionStall,
            long connectionEpisodeId,
            boolean velocityOwnsRecovery,
            boolean pendingSetback,
            long nowNanos
    ) {
        observeConnectionEpisode(connectionEpisodeId);

        if (pendingSetback || correctionInFlight) {
            return false;
        }

        // Authoritative state repair (invalid/NaN/world-invalid positions, etc.)
        // must not be blocked by a heuristic owner. The existing pending-setback
        // guard above still prevents two teleports from being in flight at once.
        if (priority == Priority.AUTHORITATIVE) {
            return true;
        }

        // After our teleport is acknowledged, give the client a short clean
        // window before another independent check can rubberband it again.
        if (nowNanos < holdUntilNanos) {
            return false;
        }

        // A re-applied server velocity is already the physical correction for
        // this short window. Do not stack a teleport on top of it.
        if (velocityOwnsRecovery) {
            return false;
        }

        // A confirmed ConnectionStall episode owns physical correction. Other
        // checks may continue flagging/recording evidence but cannot teleport.
        if (connectionEpisodeId != 0L) {
            return sourceIsConnectionStall && !connectionCorrectionApplied;
        }

        return true;
    }

    public synchronized void markApplied(
            boolean sourceIsConnectionStall,
            long connectionEpisodeId
    ) {
        observeConnectionEpisode(connectionEpisodeId);
        correctionInFlight = true;

        if (connectionEpisodeId != 0L && sourceIsConnectionStall) {
            connectionCorrectionApplied = true;
        }
    }

    public synchronized void acknowledge(long nowNanos) {
        if (!correctionInFlight) {
            return;
        }

        correctionInFlight = false;
        holdUntilNanos = Math.max(holdUntilNanos, nowNanos + POST_ACK_HOLD_NANOS);
    }

    private void observeConnectionEpisode(long connectionEpisodeId) {
        if (observedConnectionEpisodeId == connectionEpisodeId) {
            return;
        }

        observedConnectionEpisodeId = connectionEpisodeId;
        connectionCorrectionApplied = false;
    }
}
