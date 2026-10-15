package ac.grim.grimac.predictionengine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PointThreeLegacyTest {

    @Test
    void legacyApexDropsSubThresholdVelocityBeforeHiddenTick() {
        double initialY = 0.0030162615090425808D;
        double minimumMovement = 0.005D;
        double gravityFromZero = -0.0784000015258789D;

        double correction = PointThreeEstimator.legacySkippedTickInitialCorrection(
                initialY,
                minimumMovement,
                true
        );

        assertEquals(-initialY, correction, 1.0E-15D);
        assertEquals(
                gravityFromZero,
                initialY + correction + gravityFromZero,
                1.0E-15D
        );
    }

    @Test
    void modernOrAboveThresholdVelocityIsNotAdjusted() {
        assertEquals(
                0.0D,
                PointThreeEstimator.legacySkippedTickInitialCorrection(
                        0.0030162615090425808D,
                        0.005D,
                        false
                ),
                0.0D
        );
        assertEquals(
                0.0D,
                PointThreeEstimator.legacySkippedTickInitialCorrection(
                        0.006D,
                        0.005D,
                        true
                ),
                0.0D
        );
    }
}
