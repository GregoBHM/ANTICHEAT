package ac.grim.grimac.utils.item;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsumeTimingPolicyTest {

    @Test
    void normalVanillaDurationIsAccepted() {
        ConsumeTimingPolicy.Result result = ConsumeTimingPolicy.evaluate(
                1600L,
                1600L,
                0.72D,
                120L,
                40L,
                1.0D,
                0.45D
        );

        assertFalse(result.violation());
    }

    @Test
    void realFastEatRemainsDetectable() {
        ConsumeTimingPolicy.Result result = ConsumeTimingPolicy.evaluate(
                1600L,
                450L,
                0.72D,
                120L,
                40L,
                1.0D,
                0.45D
        );

        assertTrue(result.violation());
    }

    @Test
    void highPingAddsBoundedLenience() {
        ConsumeTimingPolicy.Result normalPing = ConsumeTimingPolicy.evaluate(
                1600L,
                900L,
                0.72D,
                120L,
                40L,
                1.0D,
                0.45D
        );
        ConsumeTimingPolicy.Result highPing = ConsumeTimingPolicy.evaluate(
                1600L,
                900L,
                0.72D,
                120L,
                1000L,
                1.0D,
                0.45D
        );

        assertTrue(normalPing.violation());
        assertFalse(highPing.violation());
        assertTrue(highPing.pingLenienceMillis() <= 180L);
    }

    @Test
    void lowNetworkConfidenceNeverBecomesFastConsumeVl() {
        ConsumeTimingPolicy.Result result = ConsumeTimingPolicy.evaluate(
                1600L,
                300L,
                0.72D,
                120L,
                40L,
                0.30D,
                0.45D
        );

        assertFalse(result.violation());
    }
}
