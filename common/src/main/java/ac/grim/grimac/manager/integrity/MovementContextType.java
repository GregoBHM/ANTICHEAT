package ac.grim.grimac.manager.integrity;

/**
 * Short-lived, source-aware movement contexts for server-authorized mechanics.
 *
 * <p>These are intentionally narrow. No context globally exempts a player from Grim.
 * A context only tells the integrity layer which exceptional movement state the server
 * intentionally created.</p>
 */
public enum MovementContextType {
    /** Server-authorized velocity/knockback. */
    KNOCKBACK(false, false, true),
    /** Upward/forward launch. */
    LAUNCH(false, false, true),
    /** Short horizontal burst. */
    DASH(false, false, true),
    /** Pull toward a point/entity. */
    PULL(false, false, true),
    /** Server-authorized teleport. Teleports may legitimately reset fall state. */
    TELEPORT(true, true, false),
    /** Server intentionally freezes player control; packet-stall integrity should not infer Blink from it. */
    FREEZE(true, false, false),
    /** Root/snare that intentionally prevents movement. */
    ROOT(true, false, false),
    /** Server-authorized flight state. */
    FLIGHT(true, true, false),
    /** Levitation-like effect that can reset/alter normal fall accumulation. */
    LEVITATION(false, true, false),
    /** Explicit trusted fall reset for a server mechanic. */
    FALL_RESET(false, true, false);

    private final boolean suppressConnectionStall;
    private final boolean resetFallLedger;
    private final boolean trustedVelocityOverride;

    MovementContextType(boolean suppressConnectionStall, boolean resetFallLedger, boolean trustedVelocityOverride) {
        this.suppressConnectionStall = suppressConnectionStall;
        this.resetFallLedger = resetFallLedger;
        this.trustedVelocityOverride = trustedVelocityOverride;
    }

    public boolean suppressesConnectionStall() {
        return suppressConnectionStall;
    }

    public boolean resetsFallLedger() {
        return resetFallLedger;
    }

    public boolean allowsTrustedVelocityOverride() {
        return trustedVelocityOverride;
    }
}
