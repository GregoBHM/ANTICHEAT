package ac.grim.grimac.checks.impl.integrity;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.manager.integrity.IntegritySignal;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.item.ItemBehaviourRegistry;
import ac.grim.grimac.utils.item.ItemUseResetPolicy;
import ac.grim.grimac.utils.item.ItemUseSessionTracker;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.DiggingAction;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientHeldItemChange;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerBlockPlacement;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerDigging;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientUseItem;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

@CheckData(name = "FastConsume", alternativeName = "NoSlow", stableKey = "grim.interaction.fast_consume",
        description = "Finished consuming an item faster than the client protocol allows", setback = -1, decay = 0.20)
public final class ConsumeTiming extends Check implements PacketReceiveListener {
    private boolean enabled;
    private double minimumRatio;
    private long absoluteLenienceMillis;
    private long flagCooldownMillis;
    private long consumeSessionGraceMillis;
    private long lastFlagMillis;
    private boolean requireItemMatch;
    private boolean preventEnabled;
    private double preventionMinimumConfidence;

    public ConsumeTiming(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.PLAYER_DIGGING) {
            WrapperPlayClientPlayerDigging digging = new WrapperPlayClientPlayerDigging(event);
            if (digging.getAction() == DiggingAction.RELEASE_USE_ITEM) {
                player.itemUseSessionTracker.end(ItemUseResetPolicy.Reason.CLIENT_RELEASE);
            }
            return;
        }

        if (event.getPacketType() == PacketType.Play.Client.HELD_ITEM_CHANGE) {
            int slot = new WrapperPlayClientHeldItemChange(event).getSlot();
            if (slot >= 0 && slot <= 8
                    && slot != player.packetStateData.lastSlotSelected) {
                player.itemUseSessionTracker.end(ItemUseResetPolicy.Reason.REAL_SLOT_CHANGE);
            }
            return;
        }

        if (event.getPacketType() == PacketType.Play.Client.CLOSE_WINDOW) {
            player.itemUseSessionTracker.end(ItemUseResetPolicy.Reason.OPEN_WINDOW);
            return;
        }

        InteractionHand hand = null;
        if (event.getPacketType() == PacketType.Play.Client.USE_ITEM) {
            hand = new WrapperPlayClientUseItem(event).getHand();
        } else if (event.getPacketType() == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
            WrapperPlayClientPlayerBlockPlacement placement = new WrapperPlayClientPlayerBlockPlacement(event);
            if (placement.getFace() == BlockFace.OTHER) {
                hand = InteractionHand.MAIN_HAND;
            }
        }

        if (hand != null) {
            beginUse(hand, System.currentTimeMillis());
        }
    }

    private synchronized void beginUse(
            @NotNull InteractionHand hand,
            long nowMillis
    ) {
        ItemStack item = player.inventory.getItemInHand(hand);
        if (item == null) return;

        if (!ItemBehaviourRegistry.getItemBehaviour(player, item.getType())
                .canUse(item, player.compensatedWorld, player, hand)) {
            return;
        }

        player.itemUseSessionTracker.begin(
                hand,
                item,
                nowMillis,
                player.lastTransactionReceived.get()
        );
    }

    public synchronized ConsumeDecision evaluateServerConsume(
            long nowMillis,
            @NotNull String serverMaterial
    ) {
        ItemUseSessionTracker.Session state =
                player.itemUseSessionTracker.sessionForConsume(
                        nowMillis,
                        consumeSessionGraceMillis
                );

        player.itemUseSessionTracker.end(
                ItemUseResetPolicy.Reason.SERVER_CONSUME,
                nowMillis
        );

        if (!enabled || state == null || state.expectedMillis() <= 0L) {
            return ConsumeDecision.IGNORE;
        }

        if (GrimAPI.INSTANCE.getInteractionContextManager()
                .suppressesConsumeTiming(player.uuid)) {
            return ConsumeDecision.IGNORE;
        }

        String normalizedServerItem =
                ItemUseSessionTracker.normalizeItemKey(serverMaterial);

        if (requireItemMatch
                && !itemKeysMatch(state.itemKey(), normalizedServerItem)) {
            return ConsumeDecision.rewardOnly();
        }

        long actual = Math.max(0L, nowMillis - state.startedAtMillis());
        long pingLenience =
                Math.min(180L, Math.max(0L, player.getTransactionPing()) / 4L);
        long lenience = absoluteLenienceMillis + pingLenience;
        long minimumAllowed = Math.max(
                0L,
                Math.round(state.expectedMillis() * minimumRatio) - lenience
        );

        if (actual >= minimumAllowed) {
            return ConsumeDecision.rewardOnly();
        }

        double confidence = GrimAPI.INSTANCE
                .getLagProtectionManager()
                .heuristicConfidence(player);

        if (confidence < 0.45D) {
            return ConsumeDecision.allowOnly();
        }

        boolean prevent = preventEnabled
                && confidence >= preventionMinimumConfidence
                && shouldModifyPackets();

        return new ConsumeDecision(
                prevent,
                true,
                false,
                state.sessionId(),
                componentFor(state.itemKey()),
                state.itemKey(),
                serverMaterial.toLowerCase(Locale.ROOT),
                state.expectedMillis(),
                actual,
                minimumAllowed,
                state.startTransaction(),
                state.refreshes(),
                state.repeatedUses(),
                state.interactionContext(),
                player.getTransactionPing(),
                confidence
        );
    }

    public void applyServerConsumeDecision(@NotNull ConsumeDecision decision) {
        if (decision.reward) {
            reward();
            return;
        }

        if (!decision.violation) return;

        long now = System.currentTimeMillis();
        if (now - lastFlagMillis < flagCooldownMillis) return;
        lastFlagMillis = now;

        double corr = GrimAPI.INSTANCE
                .getIntegrityCorrelationManager()
                .record(player, IntegritySignal.FAST_CONSUME);

        flagComponent(
                decision.component,
                "session=" + decision.sessionId
                        + " item=" + decision.itemKey
                        + " serverItem=" + decision.serverItem
                        + " expected=" + decision.expectedMillis + "ms"
                        + " actual=" + decision.actualMillis + "ms"
                        + " min=" + decision.minimumAllowedMillis + "ms"
                        + " tx=" + decision.startTransaction
                        + " refreshes=" + decision.refreshes
                        + " repeatedUse=" + decision.repeatedUses
                        + " interaction=" + decision.interactionContext
                        + " ping=" + decision.pingMillis
                        + " confidence=" + String.format(Locale.ROOT, "%.2f", decision.confidence)
                        + " prevented=" + decision.prevent
                        + " corr=" + String.format(Locale.ROOT, "%.2f", corr)
        );
    }

    public void completeServerConsume(
            long nowMillis,
            @NotNull String serverMaterial
    ) {
        ConsumeDecision decision =
                evaluateServerConsume(nowMillis, serverMaterial);
        applyServerConsumeDecision(decision);
    }

    public synchronized void reset() {
        player.itemUseSessionTracker.clear();
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        super.onReload(config);
        enabled = config.getBooleanElse("interaction-integrity.enabled", true)
                && config.getBooleanElse(
                "interaction-integrity.consume.enabled",
                true
        );
        minimumRatio = clamp(
                config.getDoubleElse(
                        "interaction-integrity.consume.minimum-ratio",
                        0.72D
                ),
                0.20D,
                1.0D
        );
        absoluteLenienceMillis = clamp(
                config.getLongElse(
                        "interaction-integrity.consume.absolute-lenience-ms",
                        120L
                ),
                0L,
                1000L
        );
        consumeSessionGraceMillis = clamp(
                config.getLongElse(
                        "interaction-integrity.consume.session-end-grace-ms",
                        500L
                ),
                0L,
                3000L
        );
        flagCooldownMillis = clamp(
                config.getLongElse(
                        "interaction-integrity.consume.flag-cooldown-ms",
                        1200L
                ),
                250L,
                30_000L
        );
        requireItemMatch = config.getBooleanElse(
                "interaction-integrity.consume.require-item-match",
                true
        );
        preventEnabled = config.getBooleanElse(
                "interaction-integrity.consume.prevent.enabled",
                true
        );
        preventionMinimumConfidence = clamp(
                config.getDoubleElse(
                        "interaction-integrity.consume.prevent.minimum-confidence",
                        0.60D
                ),
                0.0D,
                1.0D
        );
    }

    private static String componentFor(String itemKey) {
        String key = itemKey == null ? "" : itemKey;
        if (key.contains("potion") || key.contains("milk")
                || key.contains("honey")) {
            return "fastconsume.potion";
        }
        return "fastconsume.food";
    }

    private static boolean itemKeysMatch(
            String packetKey,
            String serverKey
    ) {
        String left = ItemUseSessionTracker.normalizeItemKey(packetKey);
        String right = ItemUseSessionTracker.normalizeItemKey(serverKey);
        return left.equals(right)
                || left.endsWith("_" + right)
                || right.endsWith("_" + left);
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    public static final class ConsumeDecision {
        static final ConsumeDecision IGNORE = new ConsumeDecision(
                false, false, false,
                0L, "", "", "",
                0L, 0L, 0L, 0,
                0, 0, "none", 0L, 0.0D
        );

        private final boolean prevent;
        private final boolean violation;
        private final boolean reward;
        private final long sessionId;
        private final String component;
        private final String itemKey;
        private final String serverItem;
        private final long expectedMillis;
        private final long actualMillis;
        private final long minimumAllowedMillis;
        private final int startTransaction;
        private final int refreshes;
        private final int repeatedUses;
        private final String interactionContext;
        private final long pingMillis;
        private final double confidence;

        ConsumeDecision(
                boolean prevent,
                boolean violation,
                boolean reward,
                long sessionId,
                String component,
                String itemKey,
                String serverItem,
                long expectedMillis,
                long actualMillis,
                long minimumAllowedMillis,
                int startTransaction,
                int refreshes,
                int repeatedUses,
                String interactionContext,
                long pingMillis,
                double confidence
        ) {
            this.prevent = prevent;
            this.violation = violation;
            this.reward = reward;
            this.sessionId = sessionId;
            this.component = component;
            this.itemKey = itemKey;
            this.serverItem = serverItem;
            this.expectedMillis = expectedMillis;
            this.actualMillis = actualMillis;
            this.minimumAllowedMillis = minimumAllowedMillis;
            this.startTransaction = startTransaction;
            this.refreshes = refreshes;
            this.repeatedUses = repeatedUses;
            this.interactionContext = interactionContext;
            this.pingMillis = pingMillis;
            this.confidence = confidence;
        }

        static ConsumeDecision rewardOnly() {
            return new ConsumeDecision(
                    false, false, true,
                    0L, "", "", "",
                    0L, 0L, 0L, 0,
                    0, 0, "none", 0L, 0.0D
            );
        }

        static ConsumeDecision allowOnly() {
            return new ConsumeDecision(
                    false, false, false,
                    0L, "", "", "",
                    0L, 0L, 0L, 0,
                    0, 0, "none", 0L, 0.0D
            );
        }

        public boolean shouldPrevent() {
            return prevent;
        }

        public boolean isViolation() {
            return violation;
        }
    }
}
