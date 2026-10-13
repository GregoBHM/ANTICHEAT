package ac.grim.grimac.manager.integrity;

/** Narrow, short-lived compatibility scopes for trusted plugin-authored interactions. */
public enum InteractionContextType {
    CUSTOM_INVENTORY(true),
    CUSTOM_ITEM_ACTION(true);

    private final boolean suppressInventoryFrequency;

    InteractionContextType(boolean suppressInventoryFrequency) {
        this.suppressInventoryFrequency = suppressInventoryFrequency;
    }

    public boolean suppressInventoryFrequency() {
        return suppressInventoryFrequency;
    }
}
