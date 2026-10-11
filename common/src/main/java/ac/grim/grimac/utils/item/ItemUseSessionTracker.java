package ac.grim.grimac.utils.item;

import ac.grim.grimac.GrimAPI;
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
    private volatile long consumableStaleExtraMillis = 3000L;

    public ItemUseSessionTracker(@NotNull GrimPlayer player) {
        this.player = player;
    }

    public synchronized @NotNull Session begin(
            @NotNull InteractionHand hand,
            @NotNull ItemStack item,
            long nowMillis,
            int startTransaction
    ) {
        expireStaleConsumableLocked(nowMillis);

        String itemKey = normalizeItemKey(item.getType().getName().getKey());
        int slot = hand == InteractionHand.MAIN_HAND
                ? player.packetStateData.lastSlotSelected
                : -1;
        long expectedMillis = expectedDurationMillis(item);
        int legacyData = item.getLegacyData();

        if (active != null
                && matchesIdentity(
                active,
                hand,
                slot,
                itemKey,
                legacyData,
                expectedMillis
        )) {
            if (active.expectedMillis > 0L) {
                active = active.withRepeatedUses(active.repeatedUses + 1);
                return active;
            }

            // A server metadata packet may have created an untimed physical
            // session before the client USE packet became visible to Grim.
            // Upgrade that observation into a real timed session instead of
            // inheriting an unknown start time.
            active = null;
        } else if (active != null) {
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
        expireStaleConsumableLocked(nowMillis);

        String itemKey = normalizeItemKey(item.getType().getName().getKey());
        int slot = hand == InteractionHand.MAIN_HAND
                ? player.packetStateData.lastSlotSelected
                : -1;
        int legacyData = item.getLegacyData();
        long observedExpectedMillis = expectedDurationMillis(item);

        if (active != null
                && matchesIdentity(
                active,
                hand,
                slot,
                itemKey,
                legacyData,
                observedExpectedMillis
        )) {
            return active;
        }

        if (active != null) {
            endInternal(ItemUseResetPolicy.Reason.REAL_ITEM_CHANGE, nowMillis);
        }

        // A server metadata update can prove that an item is physically active,
        // but it cannot prove when the client started consuming it. Mark the
        // timing as unknown (0 ms expected) so FastConsume never derives a
        // violation from a session whose start packet was not observed.
        active = new Session(
                sequence.incrementAndGet(),
                hand,
                slot,
                itemKey,
                nowMillis,
                transaction,
                0L,
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
        long nowMillis = System.currentTimeMillis();
        expireStaleConsumableLocked(nowMillis);
        if (active == null || item == null) return;
        if (!matches(active, hand, item)) return;
        active = active.withRefreshes(
                active.refreshes + 1,
                nowMillis
        );
    }

    public synchronized boolean isActive() {
        expireStaleConsumableLocked(System.currentTimeMillis());
        return active != null;
    }

    public synchronized boolean isActive(@NotNull InteractionHand hand) {
        expireStaleConsumableLocked(System.currentTimeMillis());
        return active != null && active.hand == hand;
    }

    public synchronized @Nullable Session activeSession() {
        expireStaleConsumableLocked(System.currentTimeMillis());
        return active;
    }

    public synchronized @Nullable Session sessionForConsume(long nowMillis) {
        return sessionForConsume(nowMillis, DEFAULT_RECENT_END_GRACE_MILLIS);
    }

    public synchronized @Nullable Session sessionForConsume(
            long nowMillis,
            long recentEndGraceMillis
    ) {
        expireStaleConsumableLocked(nowMillis);

        if (active != null && active.expectedMillis > 0L) {
            return active;
        }

        long grace = Math.max(0L, recentEndGraceMillis);
        if (lastEnded != null
                && lastEnded.expectedMillis > 0L
                && isConsumeCompletionReason(lastEnded.endReason)
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

    public synchronized void cancelConsume(
            long nowMillis,
            long recentEndGraceMillis
    ) {
        if (active != null) {
            endInternal(ItemUseResetPolicy.Reason.PLUGIN_CANCELLED, nowMillis);
            return;
        }

        long grace = Math.max(0L, recentEndGraceMillis);
        long age = nowMillis - lastEndedAtMillis;
        if (lastEnded != null
                && age >= 0L
                && age <= grace
                && isConsumeCompletionReason(lastEnded.endReason)) {
            lastEnded = lastEnded.withEndReason(
                    ItemUseResetPolicy.Reason.PLUGIN_CANCELLED
            );
            lastEndedAtMillis = nowMillis;
        }
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
        expireStaleConsumableLocked(System.currentTimeMillis());

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
                        normalizeItemKey(
                                replacementItem.getType().getName().getKey()
                        ),
                        replacementItem.getLegacyData(),
                        expectedDurationMillis(replacementItem)
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

        // Repeated USE packets may require a server-side active-item reset on
        // some protocol versions, but they still belong to the same physical
        // consume episode. Keeping the session start time prevents packet spam
        // from restarting FastConsume's clock.
        if (reason != ItemUseResetPolicy.Reason.REPEATED_USE) {
            player.packetStateData.setSlowedByUsingItem(false);
            endInternal(reason, System.currentTimeMillis());
        }
        return true;
    }

    public synchronized boolean matchesActive(
            @NotNull InteractionHand hand,
            @NotNull ItemStack item
    ) {
        expireStaleConsumableLocked(System.currentTimeMillis());
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
                item.getLegacyData(),
                expectedDurationMillis(item)
        );
    }

    private static boolean matchesIdentity(
            @NotNull Session session,
            @NotNull InteractionHand hand,
            int slot,
            @NotNull String itemKey,
            int legacyData,
            long expectedMillis
    ) {
        return session.hand == hand
                && session.slot == slot
                && samePhysicalIdentity(
                session.itemKey,
                session.legacyData,
                session.expectedMillis,
                itemKey,
                legacyData,
                expectedMillis
        );
    }

    static boolean samePhysicalIdentity(
            @NotNull String leftKey,
            int leftLegacyData,
            long leftExpectedMillis,
            @NotNull String rightKey,
            int rightLegacyData,
            long rightExpectedMillis
    ) {
        boolean compatibleDuration = leftExpectedMillis <= 0L
                || rightExpectedMillis <= 0L
                || leftExpectedMillis == rightExpectedMillis;

        return leftKey.equals(rightKey)
                && leftLegacyData == rightLegacyData
                && compatibleDuration;
    }

    public static boolean isCompatibleRefresh(
            @Nullable ItemStack currentItem,
            @Nullable ItemStack replacementItem
    ) {
        if (currentItem == null || replacementItem == null) {
            return false;
        }

        return samePhysicalIdentity(
                normalizeItemKey(
                        currentItem.getType().getName().getKey()
                ),
                currentItem.getLegacyData(),
                expectedDurationMillis(currentItem),
                normalizeItemKey(
                        replacementItem.getType().getName().getKey()
                ),
                replacementItem.getLegacyData(),
                expectedDurationMillis(replacementItem)
        );
    }

    private static boolean isConsumeCompletionReason(
            @Nullable ItemUseResetPolicy.Reason reason
    ) {
        return reason == ItemUseResetPolicy.Reason.CLIENT_RELEASE
                || reason == ItemUseResetPolicy.Reason.SERVER_METADATA
                || reason == ItemUseResetPolicy.Reason.SERVER_CONSUME;
    }

    public synchronized void configureConsumableStaleExtraMillis(
            long staleExtraMillis
    ) {
        consumableStaleExtraMillis = clamp(
                staleExtraMillis,
                500L,
                10_000L
        );
        expireStaleConsumableLocked(System.currentTimeMillis());
    }

    private void expireStaleConsumableLocked(long nowMillis) {
        if (active == null) return;
        if (!isConsumableStale(
                active.startedAtMillis,
                active.expectedMillis,
                nowMillis,
                consumableStaleExtraMillis
        )) {
            return;
        }

        endInternal(
                ItemUseResetPolicy.Reason.STALE_TIMEOUT,
                nowMillis
        );
    }

    static boolean isConsumableStale(
            long startedAtMillis,
            long expectedMillis,
            long nowMillis,
            long staleExtraMillis
    ) {
        if (expectedMillis <= 0L) return false;
        long maximumAge = expectedMillis + Math.max(0L, staleExtraMillis);
        return nowMillis - startedAtMillis > maximumAge;
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

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
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
            long lastRefreshAtMillis,
            int legacyData,
            @Nullable ItemUseResetPolicy.Reason endReason,
            @NotNull String interactionContext
    ) {
        Session withRefreshes(int value, long nowMillis) {
            return new Session(
                    sessionId,
                    hand,
                    slot,
                    itemKey,
                    startedAtMillis,
                    startTransaction,
                    expectedMillis,
                    value,
                    repeatedUses,
                    nowMillis,
                    legacyData,
                    endReason,
                    interactionContext
            );
        }

        Session withRepeatedUses(int value) {
            return new Session(
                    sessionId,
                    hand,
                    slot,
                    itemKey,
                    startedAtMillis,
                    startTransaction,
                    expectedMillis,
                    refreshes,
                    value,
                    lastRefreshAtMillis,
                    legacyData,
                    endReason,
                    interactionContext
            );
        }

        Session withEndReason(@NotNull ItemUseResetPolicy.Reason reason) {
            return new Session(
                    sessionId,
                    hand,
                    slot,
                    itemKey,
                    startedAtMillis,
                    startTransaction,
                    expectedMillis,
                    refreshes,
                    repeatedUses,
                    lastRefreshAtMillis,
                    legacyData,
                    reason,
                    interactionContext
            );
        }

        public boolean active() {
            return endReason == null;
        }
    }
}
