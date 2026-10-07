package ac.grim.grimac.manager.integrity;

/**
 * Lightweight movement-environment labels used to reduce false confidence without disabling Grim checks.
 * These are context signals only: none of them grants a Phase/Reach/Timer bypass.
 */
public enum EnvironmentContext {
    NORMAL,
    COBWEB,
    CLIMBABLE,
    WATER,
    LAVA,
    COMPLEX_COLLISION,
    PISTON,
    RECENT_BLOCK_CHANGE
}
