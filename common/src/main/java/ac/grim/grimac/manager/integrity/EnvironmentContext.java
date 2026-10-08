package ac.grim.grimac.manager.integrity;

/**
 * Lightweight movement-environment labels used to reduce false confidence
 * without disabling Grim checks.
 *
 * These are context signals only. None of them grants a Phase, Reach,
 * Timer or Simulation bypass.
 */
public enum EnvironmentContext {
    NORMAL,
    COBWEB,
    STUCK_MOVEMENT,
    CLIMBABLE,
    WATER,
    LAVA,
    COMPLEX_COLLISION,
    PISTON,
    RECENT_BLOCK_CHANGE
}
