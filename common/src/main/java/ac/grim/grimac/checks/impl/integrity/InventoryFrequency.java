package ac.grim.grimac.checks.impl.integrity;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.manager.integrity.IntegritySignal;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientClickWindow;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Weighted multi-window inventory click frequency. Complements, not replaces, PacketOrder/MultiActions. */
@CheckData(name = "InventoryFrequency", alternativeName = "MultiActions", stableKey = "grim.interaction.inventory_frequency",
        description = "Sent an abnormal weighted frequency of inventory actions", setback = -1, decay = 0.20)
public final class InventoryFrequency extends Check implements PacketReceiveListener {
    private static final int RING_SIZE = 128;
    private final long[] times = new long[RING_SIZE];
    private final float[] weights = new float[RING_SIZE];
    private int index;
    private int count;
    private double buffer;
    private long lastFlagNanos;

    private boolean enabled;
    private double shortLimit;
    private double oneSecondLimit;
    private double longLimit;
    private double flagBuffer;
    private long flagCooldownNanos;
    private boolean requireProtocolCorrelation;
    private long protocolCausalWindowMillis;
    private double standaloneExtremeRatio;

    public InventoryFrequency(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!enabled || event.getPacketType() != PacketType.Play.Client.CLICK_WINDOW) return;
        if (player.serverOpenedInventoryThisTick) return;
        if (GrimAPI.INSTANCE.getInteractionContextManager().suppressesInventoryFrequency(player.uuid)) {
            reset();
            return;
        }

        WrapperPlayClientClickWindow click = new WrapperPlayClientClickWindow(event);
        float weight = weight(click.getWindowClickType());
        long now = System.nanoTime();
        add(now, weight);

        double shortScore = scoreSince(now - TimeUnit.MILLISECONDS.toNanos(250L));
        double oneSecondScore = scoreSince(now - TimeUnit.SECONDS.toNanos(1L));
        double longScore = scoreSince(now - TimeUnit.SECONDS.toNanos(4L));
        double excess = Math.max(shortScore / shortLimit,
                Math.max(oneSecondScore / oneSecondLimit, longScore / longLimit)) - 1.0D;

        if (excess > 0.0D) {
            double confidence = GrimAPI.INSTANCE.getLagProtectionManager().heuristicConfidence(player);
            buffer += excess * confidence;
            if (buffer >= flagBuffer && now - lastFlagNanos >= flagCooldownNanos) {
                double peakRatio = excess + 1.0D;
                boolean protocolEvidence = GrimAPI.INSTANCE.getIntegrityCorrelationManager().hasRecentWithin(
                        player.uuid, IntegritySignal.PACKET_ORDER, protocolCausalWindowMillis)
                        || GrimAPI.INSTANCE.getIntegrityCorrelationManager().hasRecentWithin(
                        player.uuid, IntegritySignal.MULTI_ACTION, protocolCausalWindowMillis);
                boolean strongStandalone = peakRatio >= standaloneExtremeRatio;
                if (!requireProtocolCorrelation || protocolEvidence || strongStandalone) {
                    lastFlagNanos = now;
                    double corr = GrimAPI.INSTANCE.getIntegrityCorrelationManager()
                            .record(player, IntegritySignal.INVENTORY_FREQUENCY);
                    flag("type=" + click.getWindowClickType().name().toLowerCase(Locale.ROOT)
                            + " w250=" + format(shortScore) + " w1s=" + format(oneSecondScore)
                            + " w4s=" + format(longScore) + " ratio=" + format(peakRatio)
                            + " protocol=" + protocolEvidence + " corr=" + format(corr));
                    buffer *= 0.45D;
                } else {
                    // Frequency alone remains evidence, not a punishment-grade flag. This is especially important
                    // for fast 1.8 inventory users and custom GUIs that can legitimately produce short bursts.
                    buffer *= 0.72D;
                }
            }
        } else {
            buffer = Math.max(0.0D, buffer - 0.12D);
        }
    }

    private static float weight(WrapperPlayClientClickWindow.WindowClickType type) {
        return switch (type) {
            case QUICK_MOVE -> 0.80F;
            case THROW -> 0.70F;
            case PICKUP_ALL -> 0.75F;
            case QUICK_CRAFT -> 0.55F;
            case SWAP -> 0.90F;
            case CLONE -> 0.50F;
            case PICKUP -> 1.00F;
            case UNKNOWN -> 1.25F;
        };
    }

    private void add(long now, float weight) {
        times[index] = now;
        weights[index] = weight;
        index = (index + 1) % RING_SIZE;
        if (count < RING_SIZE) count++;
    }

    private double scoreSince(long minimumNanos) {
        double score = 0.0D;
        for (int i = 0; i < count; i++) {
            int current = index - 1 - i;
            if (current < 0) current += RING_SIZE;
            if (times[current] < minimumNanos) break;
            score += weights[current];
        }
        return score;
    }

    public void reset() {
        index = 0;
        count = 0;
        buffer = 0.0D;
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        super.onReload(config);
        enabled = config.getBooleanElse("interaction-integrity.enabled", true)
                && config.getBooleanElse("interaction-integrity.inventory-frequency.enabled", true);
        double profile = profileLimitMultiplier(config);
        shortLimit = Math.max(2.0D, config.getDoubleElse("interaction-integrity.inventory-frequency.short-limit", 7.0D) * profile);
        oneSecondLimit = Math.max(shortLimit, config.getDoubleElse("interaction-integrity.inventory-frequency.one-second-limit", 22.0D) * profile);
        longLimit = Math.max(oneSecondLimit, config.getDoubleElse("interaction-integrity.inventory-frequency.four-second-limit", 60.0D) * profile);
        flagBuffer = Math.max(0.25D, config.getDoubleElse("interaction-integrity.inventory-frequency.flag-buffer", 1.5D));
        long cooldown = clamp(config.getLongElse("interaction-integrity.inventory-frequency.flag-cooldown-ms", 1500L), 250L, 30_000L);
        flagCooldownNanos = TimeUnit.MILLISECONDS.toNanos(cooldown);
        requireProtocolCorrelation = config.getBooleanElse(
                "interaction-integrity.inventory-frequency.require-protocol-correlation", true);
        protocolCausalWindowMillis = clamp(config.getLongElse(
                "interaction-integrity.inventory-frequency.protocol-causal-window-ms", 1500L), 250L, 10_000L);
        standaloneExtremeRatio = Math.max(1.05D, config.getDoubleElse(
                "interaction-integrity.inventory-frequency.standalone-extreme-ratio", 1.75D));
    }

    private static double profileLimitMultiplier(ConfigManager config) {
        String profile = config.getStringElse("integrity-profile", "balanced").trim().toLowerCase(Locale.ROOT);
        return switch (profile) {
            case "safe" -> 1.25D;
            case "strict" -> 0.88D;
            default -> 1.0D;
        };
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }
}
