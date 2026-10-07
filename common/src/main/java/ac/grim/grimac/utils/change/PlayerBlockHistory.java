package ac.grim.grimac.utils.change;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.function.Predicate;

/**
 * Tracks block modifications made by a player over time.
 */
public class PlayerBlockHistory {
    private final ConcurrentLinkedDeque<BlockModification> blockHistory = new ConcurrentLinkedDeque<>();

    /**
     * Adds a new block modification to the history.
     *
     * @param modification The block modification to add
     */
    public void add(BlockModification modification) {
        blockHistory.add(modification);
    }

    /**
     * Retrieves recent modifications that match the given filter.
     *
     * @param filter Predicate to filter modifications
     * @return Filtered list of block modifications
     */
    public Iterable<BlockModification> getRecentModifications(Predicate<BlockModification> filter) {
        return blockHistory.stream().filter(filter).toList();
    }

    /**
     * Removes modifications older than the specified tick.
     *
     * @param maxTick The maximum tick age to keep
     */
    public void cleanup(int maxTick) {
        while (!blockHistory.isEmpty() && maxTick - blockHistory.peekFirst().tick() > 0) {
            blockHistory.pollFirst();
        }
    }


    /** Allocation-free lookup for checks running in movement hot paths. */
    public boolean hasRecentModification(int x, int y, int z, int currentTick, int maxAgeTicks) {
        for (BlockModification modification : blockHistory) {
            int age = currentTick - modification.tick();
            if (age < 0 || age > maxAgeTicks) continue;
            // START_DIGGING is a client-side predicted removal and must not become a Phase exemption: a cheater
            // could otherwise start-dig a wall immediately before phasing through it. Only confirmed/reconciled
            // world changes are eligible for this very small desync grace window.
            if (modification.cause() == BlockModification.Cause.START_DIGGING) continue;
            if (modification.location().x == x && modification.location().y == y && modification.location().z == z) {
                return true;
            }
        }
        return false;
    }

    public int size() {
        return blockHistory.size();
    }

    public void clear() {
        blockHistory.clear();
    }
}
