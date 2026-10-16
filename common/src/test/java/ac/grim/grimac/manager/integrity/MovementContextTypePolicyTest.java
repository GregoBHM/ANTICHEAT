package ac.grim.grimac.manager.integrity;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MovementContextTypePolicyTest {

    @Test
    void capabilityMatrixStaysNarrowAndExplicit() {
        Set<MovementContextType> velocityOverride = EnumSet.of(
                MovementContextType.KNOCKBACK,
                MovementContextType.EXPLOSION,
                MovementContextType.LAUNCH,
                MovementContextType.DASH,
                MovementContextType.PULL
        );
        Set<MovementContextType> stallSuppression = EnumSet.of(
                MovementContextType.TELEPORT,
                MovementContextType.FREEZE,
                MovementContextType.ROOT,
                MovementContextType.FLIGHT
        );
        Set<MovementContextType> fallReset = EnumSet.of(
                MovementContextType.TELEPORT,
                MovementContextType.FLIGHT,
                MovementContextType.LEVITATION,
                MovementContextType.FALL_RESET
        );

        for (MovementContextType type : MovementContextType.values()) {
            assertEquals(
                    velocityOverride.contains(type),
                    type.allowsTrustedVelocityOverride(),
                    type.name()
            );
            assertEquals(
                    stallSuppression.contains(type),
                    type.suppressesConnectionStall(),
                    type.name()
            );
            assertEquals(
                    fallReset.contains(type),
                    type.resetsFallLedger(),
                    type.name()
            );
        }
    }
}
