package ac.grim.grimac.checks.impl.integrity;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.manager.integrity.IntegritySignal;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.item.ItemBehaviourRegistry;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.component.builtin.item.ItemConsumable;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemType;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
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

/**
 * Tracks packet-side item-use start and validates the server-confirmed Bukkit consume event.
 * It deliberately ignores shields/bows/etc. and only tracks actual consumables.
 */
@CheckData(name = "FastConsume", alternativeName = "NoSlow", stableKey = "grim.interaction.fast_consume",
        description = "Finished consuming an item faster than the client protocol allows", setback = -1, decay = 0.20)
public final class ConsumeTiming extends Check implements PacketReceiveListener {
    private boolean enabled;
    private double minimumRatio;
    private long absoluteLenienceMillis;
    private long staleExtraMillis;
    private long lastFlagMillis;
    private long flagCooldownMillis;
    private boolean requireItemMatch;
    private UseState active;

    public ConsumeTiming(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!enabled) return;

        if (event.getPacketType() == PacketType.Play.Client.PLAYER_DIGGING) {
            WrapperPlayClientPlayerDigging digging = new WrapperPlayClientPlayerDigging(event);
            if (digging.getAction() == DiggingAction.RELEASE_USE_ITEM) reset();
            return;
        }
        if (event.getPacketType() == PacketType.Play.Client.HELD_ITEM_CHANGE) {
            // A real slot change ends vanilla item use. Out-of-range packets are handled elsewhere.
            int slot = new WrapperPlayClientHeldItemChange(event).getSlot();
            if (slot >= 0 && slot <= 8) reset();
            return;
        }
        if (event.getPacketType() == PacketType.Play.Client.CLOSE_WINDOW) {
            reset();
            return;
        }

        InteractionHand hand = null;
        if (event.getPacketType() == PacketType.Play.Client.USE_ITEM) {
            hand = new WrapperPlayClientUseItem(event).getHand();
        } else if (event.getPacketType() == PacketType.Play.Client.PLAYER_BLOCK_PLACEMENT) {
            WrapperPlayClientPlayerBlockPlacement placement = new WrapperPlayClientPlayerBlockPlacement(event);
            if (placement.getFace() == BlockFace.OTHER) hand = InteractionHand.MAIN_HAND;
        }

        if (hand != null) {
            beginUse(hand, System.currentTimeMillis());
        } else if (active != null && isTickPacket(event.getPacketType())) {
            long age = System.currentTimeMillis() - active.startedAtMillis;
            if (age > active.expectedMillis + staleExtraMillis) reset();
        }
    }

    private void beginUse(InteractionHand hand, long nowMillis) {
        if (GrimAPI.INSTANCE.getInteractionContextManager().suppressesConsumeTiming(player.uuid)) {
            reset();
            return;
        }

        ItemStack item = player.inventory.getItemInHand(hand);
        if (item == null) return;
        long expected = expectedDurationMillis(item);
        if (expected <= 0L) return;
        if (!ItemBehaviourRegistry.getItemBehaviour(player, item.getType())
                .canUse(item, player.compensatedWorld, player, hand)) return;

        String itemKey = item.getType().getName().getKey();
        if (active != null && active.itemKey.equals(itemKey) && nowMillis - active.startedAtMillis <= expected + staleExtraMillis) {
            // Preserve the earliest valid start; repeated right-click packets must not let FastConsume reset its stopwatch.
            return;
        }
        active = new UseState(nowMillis, expected, itemKey, hand, player.lastTransactionReceived.get());
    }

    /** Called from Bukkit MONITOR after the server has actually accepted PlayerItemConsumeEvent. */
    public void completeServerConsume(long nowMillis, @NotNull String serverMaterial) {
        UseState state = active;
        reset();
        if (!enabled || state == null) return;
        if (GrimAPI.INSTANCE.getInteractionContextManager().suppressesConsumeTiming(player.uuid)) return;
        String normalizedServerItem = normalizeItemKey(serverMaterial);
        if (requireItemMatch && !itemKeysMatch(state.itemKey, normalizedServerItem)) {
            // A different server-confirmed consumable cannot validate the packet-side stopwatch we started.
            // Treat it as ambiguous instead of turning an inventory/plugin race into FastConsume evidence.
            reward();
            return;
        }

        long actual = Math.max(0L, nowMillis - state.startedAtMillis);
        long pingLenience = Math.min(180L, Math.max(0L, player.getTransactionPing()) / 4L);
        long lenience = absoluteLenienceMillis + pingLenience;
        long minimumAllowed = Math.max(0L, Math.round(state.expectedMillis * minimumRatio) - lenience);
        if (actual >= minimumAllowed) {
            reward();
            return;
        }

        double confidence = GrimAPI.INSTANCE.getLagProtectionManager().heuristicConfidence(player);
        if (confidence < 0.45D) return;
        long now = System.currentTimeMillis();
        if (now - lastFlagMillis < flagCooldownMillis) return;
        lastFlagMillis = now;

        double corr = GrimAPI.INSTANCE.getIntegrityCorrelationManager().record(player, IntegritySignal.FAST_CONSUME);
        flag("item=" + state.itemKey + " serverItem=" + serverMaterial.toLowerCase(Locale.ROOT)
                + " expected=" + state.expectedMillis + "ms actual=" + actual + "ms min=" + minimumAllowed
                + "ms tx=" + state.startTransaction + " ping=" + player.getTransactionPing()
                + " corr=" + String.format(Locale.ROOT, "%.2f", corr));
    }

    public void reset() {
        active = null;
    }

    private long expectedDurationMillis(ItemStack item) {
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

    @Override
    public void onReload(@NotNull ConfigManager config) {
        super.onReload(config);
        enabled = config.getBooleanElse("interaction-integrity.enabled", true)
                && config.getBooleanElse("interaction-integrity.consume.enabled", true);
        minimumRatio = clamp(config.getDoubleElse("interaction-integrity.consume.minimum-ratio", 0.72D), 0.20D, 1.0D);
        absoluteLenienceMillis = clamp(config.getLongElse("interaction-integrity.consume.absolute-lenience-ms", 120L), 0L, 1000L);
        staleExtraMillis = clamp(config.getLongElse("interaction-integrity.consume.stale-extra-ms", 3000L), 500L, 10_000L);
        flagCooldownMillis = clamp(config.getLongElse("interaction-integrity.consume.flag-cooldown-ms", 1200L), 250L, 30_000L);
        requireItemMatch = config.getBooleanElse("interaction-integrity.consume.require-item-match", true);
    }

    private static boolean itemKeysMatch(String packetKey, String serverKey) {
        String left = normalizeItemKey(packetKey);
        String right = normalizeItemKey(serverKey);
        return left.equals(right) || left.endsWith("_" + right) || right.endsWith("_" + left);
    }

    private static String normalizeItemKey(String value) {
        if (value == null) return "";
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        int namespace = normalized.indexOf(':');
        if (namespace >= 0 && namespace + 1 < normalized.length()) normalized = normalized.substring(namespace + 1);
        return normalized.replace(' ', '_');
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private record UseState(long startedAtMillis, long expectedMillis, String itemKey,
                            InteractionHand hand, int startTransaction) {}
}
