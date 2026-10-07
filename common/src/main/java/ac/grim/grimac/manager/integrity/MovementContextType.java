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
    KNOCKBACK(false, false),
    /** Upward/forward launch. */
    LAUNCH(false, false),
    /** Short horizontal burst. */
    DASH(false, false),
    /** Pull toward a point/entity. */
    PULL(false, false),
    /** Server-authorized teleport. Teleports may legitimately reset fall state. */
    TELEPORT(true, true),
    /** Server intentionally freezes player control; packet-stall integrity should not infer Blink from it. */
    FREEZE(true, false),
    /** Root/snare that intentionally prevents movement. */
    ROOT(true, false),
    /** Server-authorized flight state. */
    FLIGHT(true, true),
    /** Levitation-like effect that can reset/alter normal fall accumulation. */
    LEVITATION(false, true),
    /** Explicit trusted fall reset for a server mechanic. */
    FALL_RESET(false, true);

    private final boolean suppressConnectionStall;
    private final boolean resetFallLedger;

    MovementContextType(boolean suppressConnectionStall, boolean resetFallLedger) {
        this.suppressConnectionStall = suppressConnectionStall;
        this.resetFallLedger = resetFallLedger;
    }

    public boolean suppressesConnectionStall() {
        return suppressConnectionStall;
    }

    public boolean resetsFallLedger() {
        return resetFallLedger;
    }
}
