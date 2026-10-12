package ac.grim.grimac.utils.item;

public final class LegacyItemUseResetPolicy {
    private LegacyItemUseResetPolicy() {
    }

    public static boolean preserveRepeatedUse(
            boolean legacyClient,
            boolean predictedUsingItem,
            boolean sameHand
    ) {
        return ItemUseResetPolicy.preserveRepeatedUse(
                legacyClient,
                predictedUsingItem,
                sameHand
        );
    }

    public static boolean preserveInventoryRefresh(
            boolean legacyClient,
            boolean predictedUsingItem,
            boolean sameHand,
            boolean sameItemType
    ) {
        return legacyClient && ItemUseResetPolicy.preserveInventoryRefresh(
                predictedUsingItem,
                sameHand,
                sameItemType
        );
    }
}
