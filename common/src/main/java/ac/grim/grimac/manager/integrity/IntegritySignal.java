package ac.grim.grimac.manager.integrity;

import java.util.Locale;

/** Signals used for short/long-lived correlation. None punish by themselves. */
public enum IntegritySignal {
    SIMULATION(0.5D, true),
    SELECTIVE_STALL(3.0D, false),
    AIRBORNE_STALL(2.0D, true),
    PACKET_BURST(1.0D, true),
    NO_FALL(2.5D, false),
    PHASE(3.0D, false),
    CANCELLED_BLOCK_SUPPORT(2.5D, false),
    UNSAFE_DISCONNECT(3.0D, false),
    QUEUED_ACTION(2.5D, false),
    INVENTORY_FREQUENCY(1.0D, true),
    FAST_CONSUME(2.5D, true),
    PACKET_ORDER(1.5D, false),
    MULTI_ACTION(1.5D, false),
    ATTACK_BURST(2.0D, true),
    BLOCK_PLACE_BURST(1.5D, true);

    private final double defaultWeight;
    private final boolean heuristic;

    IntegritySignal(double defaultWeight, boolean heuristic) {
        this.defaultWeight = defaultWeight;
        this.heuristic = heuristic;
    }

    public double getWeight() {
        return defaultWeight;
    }

    public boolean isHeuristic() {
        return heuristic;
    }

    public String configKey() {
        return name().toLowerCase(Locale.ROOT).replace('_', '-');
    }
}
