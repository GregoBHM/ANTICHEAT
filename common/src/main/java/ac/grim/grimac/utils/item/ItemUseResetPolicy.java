package ac.grim.grimac.utils.item;

public final class ItemUseResetPolicy {
    private ItemUseResetPolicy() {
    }

    public enum Reason {
        CLIENT_RELEASE,
        REAL_SLOT_CHANGE,
        REAL_ITEM_CHANGE,
        REPEATED_USE,
        ATTACK,
        INVENTORY_REFRESH,
        SERVER_METADATA,
        SERVER_CONSUME,
        OPEN_WINDOW,
        COOLDOWN,
        PLUGIN_CANCELLED,
        TELEPORT,
        RESPAWN,
        DEATH,
        GAMEMODE_CHANGE,
        CLEAR
    }

    public static boolean preserveRepeatedUse(
            boolean legacyClient,
            boolean predictedUsingItem,
            boolean sameHand
    ) {
        return legacyClient && predictedUsingItem && sameHand;
    }

    public static boolean preserveInventoryRefresh(
            boolean predictedUsingItem,
            boolean sameHand,
            boolean sameItemType
    ) {
        return predictedUsingItem && sameHand && sameItemType;
    }

    public static boolean shouldResetServerUse(
            Reason reason,
            boolean legacyClient,
            boolean predictedUsingItem,
            boolean sameHand,
            boolean sameItemType
    ) {
        if (!predictedUsingItem) {
            return false;
        }

        return switch (reason) {
            case INVENTORY_REFRESH ->
                    !preserveInventoryRefresh(true, sameHand, sameItemType);
            case REPEATED_USE ->
                    !preserveRepeatedUse(legacyClient, true, sameHand);
            case ATTACK, REAL_SLOT_CHANGE, REAL_ITEM_CHANGE, OPEN_WINDOW ->
                    true;
            default ->
                    false;
        };
    }
}
