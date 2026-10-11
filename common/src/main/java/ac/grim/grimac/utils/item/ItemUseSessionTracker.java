package ac.grim.grimac.utils.item;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.manager.integrity.InteractionContextManager;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemConsumable;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemType;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

public final class ItemUseSessionTracker {
    private static final long DEFAULT_RECENT_END_GRACE_MILLIS = 500L;

    private final GrimPlayer player;
    private final AtomicLong sequence = new AtomicLong();

    private Session active;
    private Session lastEnded;
    private long lastEndedAtMillis;

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
        long expectedMillis = expectedDurationMillis(item);

        if (active != null
                && active.hand == hand
                && active.slot == slot
                && active.itemKey.equals(itemKey)) {
            active = active.withRepeatedUses(active.repeatedUses + 1);
            return active;
        }

        if (active != null) {
            endInternal(ItemUseResetPolicy.Reason.REAL_ITEM_CHANGE, nowMillis);
        }

        active = new Session(
                sequence.incrementAndGet(),
                hand,
                slot,
                itemKey,
                nowMillis,
                startTransaction,
                expectedMillis,
                0,
                0,
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
        active = active.withRefreshes(active.refreshes + 1);
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

    public synchronized @Nullable Session sessionForConsume(long nowMillis) {
        return sessionForConsume(nowMillis, DEFAULT_RECENT_END_GRACE_MILLIS);
    }

    public synchronized @Nullable Session sessionForConsume(
            long nowMillis,
            long recentEndGraceMillis
    ) {
        if (active != null && active.expectedMillis > 0L) {
            return active;
        }

        long grace = Math.max(0L, recentEndGraceMillis);
        if (lastEnded != null
                && lastEnded.expectedMillis > 0L
                && nowMillis - lastEndedAtMillis <= grace) {
            return lastEnded;
        }
        return null;
    }

    public synchronized @Nullable Session finishConsume(long nowMillis) {
        Session session = sessionForConsume(nowMillis);
        if (active != null) {
            endInternal(ItemUseResetPolicy.Reason.SERVER_CONSUME, nowMillis);
        }
        return session;
    }

    public synchronized void end(@NotNull ItemUseResetPolicy.Reason reason) {
        end(reason, System.currentTimeMillis());
    }

    public synchronized void end(
            @NotNull ItemUseResetPolicy.Reason reason,
            long nowMillis
    ) {
        endInternal(reason, nowMillis);
    }

    public synchronized void clear() {
        if (active != null) {
            endInternal(ItemUseResetPolicy.Reason.CLEAR, System.currentTimeMillis());
        }
        active = null;
        lastEnded = null;
        lastEndedAtMillis = 0L;
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

        boolean sameItemType = replacementItem == null
                || active == null
                || active.itemKey.equals(normalizeItemKey(
                replacementItem.getType().getName().getKey()));

        boolean legacyClient = player.getClientVersion()
                .isOlderThanOrEquals(ClientVersion.V_1_8);

        if (!ItemUseResetPolicy.shouldResetServerUse(
                reason,
                legacyClient,
                predictedUsing,
                sameHand,
                sameItemType
        )) {
            if (reason == ItemUseResetPolicy.Reason.INVENTORY_REFRESH
                    && expectedHand != null
                    && replacementItem != null) {
                markRefresh(expectedHand, replacementItem);
            }
            return false;
        }

        GrimAPI.INSTANCE.getItemResetHandler().resetItemUsage(player.platformPlayer);
        player.packetStateData.setSlowedByUsingItem(false);
        endInternal(reason, System.currentTimeMillis());
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
        return session.hand == hand
                && session.slot == slot
                && session.itemKey.equals(normalizeItemKey(item.getType().getName().getKey()));
    }

    private void endInternal(
            @NotNull ItemUseResetPolicy.Reason reason,
            long nowMillis
    ) {
        if (active == null) return;
        lastEnded = active.withEndReason(reason);
        lastEndedAtMillis = nowMillis;
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

    public static long expectedDurationMillis(@NotNull ItemStack item) {
        ItemConsumable consumable = item.getComponentOr(ComponentTypes.CONSUMABLE, null);
        if (consumable != null && consumable.getConsumeSeconds() > 0.0F) {
            return Math.max(1L, Math.round(consumable.getConsumeSeconds() * 1000.0F));
        }

        ItemType type = item.getType();
        boolean legacyConsumable = type.hasAttribute(ItemTypes.ItemAttribute.EDIBLE)
                || type == ItemTypes.POTION
                || type == ItemTypes.MILK_BUCKET
                || type == ItemTypes.HONEY_BOTTLE
                || type == ItemTypes.SUSPICIOUS_STEW;
        if (!legacyConsumable) return 0L;
        if (type == ItemTypes.DRIED_KELP) return 800L;
        if (type == ItemTypes.HONEY_BOTTLE) return 2000L;
        return 1600L;
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

    public record Session(
            long sessionId,
            @NotNull InteractionHand hand,
            int slot,
            @NotNull String itemKey,
            long startedAtMillis,
            int startTransaction,
            long expectedMillis,
            int refreshes,
            int repeatedUses,
            @Nullable ItemUseResetPolicy.Reason endReason,
            @NotNull String interactionContext
    ) {
        Session withRefreshes(int value) {
            return new Session(sessionId, hand, slot, itemKey, startedAtMillis,
                    startTransaction, expectedMillis, value, repeatedUses,
                    endReason, interactionContext);
        }

        Session withRepeatedUses(int value) {
            return new Session(sessionId, hand, slot, itemKey, startedAtMillis,
                    startTransaction, expectedMillis, refreshes, value,
                    endReason, interactionContext);
        }

        Session withEndReason(@NotNull ItemUseResetPolicy.Reason reason) {
            return new Session(sessionId, hand, slot, itemKey, startedAtMillis,
                    startTransaction, expectedMillis, refreshes, repeatedUses,
                    reason, interactionContext);
        }
    }
}
