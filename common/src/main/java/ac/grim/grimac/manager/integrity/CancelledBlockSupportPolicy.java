package ac.grim.grimac.manager.integrity;

final class CancelledBlockSupportPolicy {
    private static final double START_BELOW = 0.10D;
    private static final double START_ABOVE = 0.18D;
    private static final double END_BELOW = 0.08D;
    private static final double END_ABOVE = 0.12D;
    private static final double MIN_TAKEOFF = 0.02D;
    private static final double MIN_LANDING = 0.02D;
    private static final double STATIONARY_EPSILON = 0.05D;

    private CancelledBlockSupportPolicy() {
    }

    static boolean isSupportUse(
            double fromFeetY,
            double toFeetY,
            double blockTopY,
            boolean previousHorizontalOverlap,
            boolean currentHorizontalOverlap
    ) {
        double deltaY = toFeetY - fromFeetY;

        boolean startedOnTop = previousHorizontalOverlap
                && fromFeetY >= blockTopY - START_BELOW
                && fromFeetY <= blockTopY + START_ABOVE;

        boolean endedOnTop = currentHorizontalOverlap
                && toFeetY >= blockTopY - END_BELOW
                && toFeetY <= blockTopY + END_ABOVE;

        if (startedOnTop && deltaY > MIN_TAKEOFF) {
            return true;
        }

        if (endedOnTop && deltaY < -MIN_LANDING) {
            return true;
        }

        return endedOnTop && Math.abs(deltaY) <= STATIONARY_EPSILON;
    }
}
