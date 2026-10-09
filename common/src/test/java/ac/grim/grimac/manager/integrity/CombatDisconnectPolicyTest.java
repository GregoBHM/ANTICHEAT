package ac.grim.grimac.manager.integrity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatDisconnectPolicyTest {

    @Test
    void preventionOnlyEvidenceIsNeverEnoughForIntegrityKill() {
        assertFalse(CombatDisconnectPolicy.shouldPunish(
                true,
                false,
                false
        ));
    }

    @Test
    void acceptedSanctionableEvidenceCanProtectCombatLogout() {
        assertTrue(CombatDisconnectPolicy.shouldPunish(
                true,
                false,
                true
        ));
    }

    @Test
    void explicitPunishAllPreservesAdministratorPolicy() {
        assertTrue(CombatDisconnectPolicy.shouldPunish(
                true,
                true,
                false
        ));
    }

    @Test
    void noActiveCombatNeverPunishes() {
        assertFalse(CombatDisconnectPolicy.shouldPunish(
                false,
                true,
                true
        ));
    }
}
