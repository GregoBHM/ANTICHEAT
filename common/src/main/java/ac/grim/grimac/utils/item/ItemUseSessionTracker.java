package ac.grim.grimac.utils.item;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks the physical lifecycle of client item use.
 *
 * <p>This class deliberately does not measure consume duration or decide whether
 * food/potions were consumed too quickly. It only provides stable physical-use
 * state for checks such as NoSlow, Sprint and MultiActions.</p>
 */
public final class ItemUseSessionTracker {
    private final GrimPlayer player;
    private final AtomicLong sequence = new AtomicLong();
    private Session active;

    public ItemUseSessionTracker(@NotNull GrimPlayer player) {
        this.player = player;
    }

    public synchronized @NotNull Session begin(
            @NotNull InteractionHand hand,
            @NotNull ItemStack item,
            long nowMillis,
            int startTransaction
    ) {
        String itemKey = normalizeItemKey(item.getType().getName().getKey());
        int slot = hand == InteractionHand.MAIN_HAND
                ? player.packetStateData.lastSlotSelected
                : -1;
        int legacyData = item.getLegacyData();

        if (active != null
                && matchesIdentity(active, hand, slot, itemKey, legacyData)) {
            active = active.withRepeatedUses(active.repeatedUses + 1);
            return active;
        }

        if (active != null) {
            endInternal(ItemUseResetPolicy.Reason.REAL_ITEM_CHANGE);
        }

        active = new Session(
                sequence.incrementAndGet(),
                hand,
                slot,
                itemKey,
                nowMillis,
                startTransaction,
                0,
                0,
                0L,
                legacyData,
                null,
                interactionContextSummary()
        );
        return active;
    }

    public synchronized @NotNull Session confirmServerActive(
            @NotNull InteractionHand hand,
            @NotNull ItemStack item,
            long nowMillis,
            int transaction
    ) {
        String itemKey = normalizeItemKey(item.getType().getName().getKey());
        int slot = hand == InteractionHand.MAIN_HAND
                ? player.packetStateData.lastSlotSelected
                : -1;
        int legacyData = item.getLegacyData();

        if (active != null
                && matchesIdentity(active, hand, slot, itemKey, legacyData)) {
            return active;
        }

        if (active != null) {
            endInternal(ItemUseResetPolicy.Reason.REAL_ITEM_CHANGE);
        }

        active = new Session(
                sequence.incrementAndGet(),
                hand,
                slot,
                itemKey,
                nowMillis,
                transaction,
                0,
                0,
                0L,
                legacyData,
                null,
                interactionContextSummary()
        );
        return active;
    }

    public synchronized void markRefresh(
            @NotNull InteractionHand hand,
            @Nullable ItemStack item
    ) {
        if (active == null || item == null) return;
        if (!matches(active, hand, item)) return;
        active = active.withRefreshes(
                active.refreshes + 1,
                System.currentTimeMillis()
        );
    }

    public synchronized boolean isActive() {
        return active != null;
    }

    public synchronized boolean isActive(@NotNull InteractionHand hand) {
        return active != null && active.hand == hand;
    }

    public synchronized @Nullable Session activeSession() {
        return active;
    }

    public synchronized void end(@NotNull ItemUseResetPolicy.Reason reason) {
        endInternal(reason);
    }

    public synchronized void end(
            @NotNull ItemUseResetPolicy.Reason reason,
            long ignoredNowMillis
    ) {
        endInternal(reason);
    }

    public synchronized void clear() {
        active = null;
    }

    public synchronized boolean requestServerReset(
            @NotNull ItemUseResetPolicy.Reason reason,
            @Nullable InteractionHand expectedHand,
            @Nullable ItemStack replacementItem
    ) {
        InteractionHand serverHand = GrimAPI.INSTANCE
                .getItemResetHandler()
                .getItemUsageHand(player.platformPlayer);

        boolean predictedUsing = player.packetStateData.isSlowedByUsingItem()
                || active != null
                || serverHand != null;

        InteractionHand activeHand = active != null
                ? active.hand
                : player.packetStateData.itemInUseHand;

        boolean sameHand = expectedHand == null
                || (activeHand == expectedHand
                && (serverHand == null || serverHand == expectedHand));

        boolean compatibleItem = replacementItem == null;
        if (replacementItem != null) {
            if (active != null) {
                compatibleItem = matchesIdentity(
                        active,
                        expectedHand == null ? active.hand : expectedHand,
                        expectedHand == InteractionHand.MAIN_HAND
                                ? player.packetStateData.lastSlotSelected
                                : active.slot,
                        normalizeItemKey(replacementItem.getType().getName().getKey()),
                        replacementItem.getLegacyData()
                );
            } else if (expectedHand != null) {
                compatibleItem = isCompatibleRefresh(
                        player.inventory.getItemInHand(expectedHand),
                        replacementItem
                );
            }
        }

        boolean legacyClient = player.getClientVersion()
                .isOlderThanOrEquals(ClientVersion.V_1_8);

        if (!ItemUseResetPolicy.shouldResetServerUse(
                reason,
                legacyClient,
                predictedUsing,
                sameHand,
                compatibleItem
        )) {
            if (reason == ItemUseResetPolicy.Reason.INVENTORY_REFRESH
                    && expectedHand != null
                    && replacementItem != null) {
                markRefresh(expectedHand, replacementItem);
            }
            return false;
        }

        GrimAPI.INSTANCE.getItemResetHandler().resetItemUsage(player.platformPlayer);

        // A repeated USE packet can require a server-side active-use resync on
        // some protocol versions, but it still belongs to the same physical
        // item-use episode. Preserve the session so prediction checks do not
        // observe a synthetic stop/start.
        if (reason != ItemUseResetPolicy.Reason.REPEATED_USE) {
            player.packetStateData.setSlowedByUsingItem(false);
            endInternal(reason);
        }
        return true;
    }

    public synchronized boolean matchesActive(
            @NotNull InteractionHand hand,
            @NotNull ItemStack item
    ) {
        return active != null && matches(active, hand, item);
    }

    private boolean matches(
            @NotNull Session session,
            @NotNull InteractionHand hand,
            @NotNull ItemStack item
    ) {
        int slot = hand == InteractionHand.MAIN_HAND
                ? player.packetStateData.lastSlotSelected
                : -1;
        return matchesIdentity(
                session,
                hand,
                slot,
                normalizeItemKey(item.getType().getName().getKey()),
                item.getLegacyData()
        );
    }

    private static boolean matchesIdentity(
            @NotNull Session session,
            @NotNull InteractionHand hand,
            int slot,
            @NotNull String itemKey,
            int legacyData
    ) {
        return session.hand == hand
                && session.slot == slot
                && samePhysicalIdentity(
                session.itemKey,
                session.legacyData,
                itemKey,
                legacyData
        );
    }

    static boolean samePhysicalIdentity(
            @NotNull String leftKey,
            int leftLegacyData,
            @NotNull String rightKey,
            int rightLegacyData
    ) {
        return itemKeysMatch(leftKey, rightKey)
                && leftLegacyData == rightLegacyData;
    }

    public static boolean isCompatibleRefresh(
            @Nullable ItemStack currentItem,
            @Nullable ItemStack replacementItem
    ) {
        if (currentItem == null || replacementItem == null) {
            return false;
        }

        return samePhysicalIdentity(
                normalizeItemKey(currentItem.getType().getName().getKey()),
                currentItem.getLegacyData(),
                normalizeItemKey(replacementItem.getType().getName().getKey()),
                replacementItem.getLegacyData()
        );
    }

    private void endInternal(@NotNull ItemUseResetPolicy.Reason reason) {
        if (active == null) return;
        active = active.withEndReason(reason);
        active = null;
    }

    private @NotNull String interactionContextSummary() {
        var contexts = GrimAPI.INSTANCE.getInteractionContextManager().getActive(player.uuid);
        if (contexts.isEmpty()) return "none";
        return contexts.stream()
                .map(context -> context.getType().name().toLowerCase(Locale.ROOT)
                        + "@" + context.getSource())
                .sorted()
                .reduce((left, right) -> left + "," + right)
                .orElse("none");
    }

    public static @NotNull String normalizeItemKey(@Nullable String value) {
        if (value == null) return "";
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        int namespace = normalized.indexOf(':');
        if (namespace >= 0 && namespace + 1 < normalized.length()) {
            normalized = normalized.substring(namespace + 1);
        }
        return normalized.replace(' ', '_');
    }

    public static boolean itemKeysMatch(
            @Nullable String first,
            @Nullable String second
    ) {
        return normalizeItemKey(first).equals(normalizeItemKey(second));
    }

    public record Session(
            long sessionId,
            @NotNull InteractionHand hand,
            int slot,
            @NotNull String itemKey,
            long startedAtMillis,
            int startTransaction,
            int refreshes,
            int repeatedUses,
            long lastRefreshAtMillis,
            int legacyData,
            @Nullable ItemUseResetPolicy.Reason endReason,
            @NotNull String interactionContext
    ) {
        Session withRefreshes(int value, long nowMillis) {
            return new Session(
                    sessionId, hand, slot, itemKey, startedAtMillis,
                    startTransaction, value, repeatedUses, nowMillis,
                    legacyData, endReason, interactionContext
            );
        }

        Session withRepeatedUses(int value) {
            return new Session(
                    sessionId, hand, slot, itemKey, startedAtMillis,
                    startTransaction, refreshes, value, lastRefreshAtMillis,
                    legacyData, endReason, interactionContext
            );
        }

        Session withEndReason(@NotNull ItemUseResetPolicy.Reason reason) {
            return new Session(
                    sessionId, hand, slot, itemKey, startedAtMillis,
                    startTransaction, refreshes, repeatedUses,
                    lastRefreshAtMillis, legacyData, reason,
                    interactionContext
            );
        }

        public boolean active() {
            return endReason == null;
        }
    }
}
