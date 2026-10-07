package ac.grim.grimac.manager.integrity;

/** Narrow, short-lived compatibility scopes for trusted plugin-authored interactions. */
public enum InteractionContextType {
    CUSTOM_CONSUME(true, false),
    PLUGIN_CANCELLED_USE(true, false),
    COMBATPLUS_COOLDOWN(true, false),
    CUSTOM_INVENTORY(false, true),
    CUSTOM_ITEM_ACTION(true, true);

    private final boolean suppressConsumeTiming;
    private final boolean suppressInventoryFrequency;

    InteractionContextType(boolean suppressConsumeTiming, boolean suppressInventoryFrequency) {
        this.suppressConsumeTiming = suppressConsumeTiming;
        this.suppressInventoryFrequency = suppressInventoryFrequency;
    }

    public boolean suppressConsumeTiming() {
        return suppressConsumeTiming;
    }

    public boolean suppressInventoryFrequency() {
        return suppressInventoryFrequency;
    }
}
