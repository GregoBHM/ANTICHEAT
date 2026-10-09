package ac.grim.grimac.manager.integrity;

final class CombatDisconnectPolicy {
    private CombatDisconnectPolicy() {
    }

    static boolean shouldPunish(
            boolean activeCombat,
            boolean punishAllCombatQuits,
            boolean sanctionableEvidence
    ) {
        return activeCombat && (punishAllCombatQuits || sanctionableEvidence);
    }
}
