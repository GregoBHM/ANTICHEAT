package ac.grim.grimac.checks.impl.integrity;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.impl.timer.Timer;
import ac.grim.grimac.checks.type.PacketSendListener;
import ac.grim.grimac.checks.type.PrePredictionPacketReceiveListener;
import ac.grim.grimac.manager.integrity.IntegritySignal;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerUpdateHealth;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying.isFlying;

/**
 * Detects packet-driven regen abuse by correlating a real server health increase
 * with a client clock lead / movement packet burst. Timer remains the primary
 * owner of extra-tick cancellation; this check creates sanctionable Regen VL and
 * may cancel only a still-unblocked severe packet during the confirmed heal window.
 */
@CheckData(name = "Regen", stableKey = "grim.interaction.regen_packet",
        description = "Accelerated regeneration through excess client tick packets",
        setback = -1, decay = 0.15)
public final class RegenPacket extends Check implements PrePredictionPacketReceiveListener, PacketSendListener {
    private static final int RING_SIZE = 96;
    private final long[] movementTimes = new long[RING_SIZE];
    private int ringIndex;
    private int ringCount;

    private boolean enabled;
    private boolean preventEnabled;
    private long healWindowNanos;
    private long abuseWindowNanos;
    private long flagCooldownNanos;
    private long minimumAheadMs;
    private long preventAheadMs;
    private int minimumPackets250;
    private double minimumHealAmount;
    private double minimumConfidence;
    private double flagBuffer;

    private float lastObservedHealth = Float.NaN;
    private long lastHealNanos;
    private double lastHealAmount;
    private long lastAbuseNanos;
    private long lastAheadMs;
    private int lastPackets250;
    private double buffer;
    private long lastFlagNanos;

    public RegenPacket(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPrePredictionPacketReceive(PacketReceiveEvent event) {
        if (!enabled || !isFlying(event.getPacketType())) return;

        long now = System.nanoTime();
        addMovement(now);

        Timer timer = player.checkManager.get(Timer.class);
        long aheadMs = timer == null ? 0L : timer.getLastAheadMillis();
        int packets250 = countSince(now - TimeUnit.MILLISECONDS.toNanos(250L));

        if (aheadMs >= minimumAheadMs) {
            lastAbuseNanos = now;
            lastAheadMs = aheadMs;
            lastPackets250 = packets250;
        }

        if (isRecent(lastHealNanos, healWindowNanos, now)
                && isRecent(lastAbuseNanos, abuseWindowNanos, now)) {
            evaluate(now, event);
        } else {
            buffer = Math.max(0.0D, buffer - 0.03D);
        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (!enabled || event.getPacketType() != PacketType.Play.Server.UPDATE_HEALTH) return;

        WrapperPlayServerUpdateHealth update = new WrapperPlayServerUpdateHealth(event);
        float current = update.getHealth();
        long now = System.nanoTime();

        if (!Float.isNaN(lastObservedHealth)
                && lastObservedHealth > 0.0F
                && current > lastObservedHealth + minimumHealAmount) {
            lastHealAmount = current - lastObservedHealth;
            lastHealNanos = now;

            if (isRecent(lastAbuseNanos, abuseWindowNanos, now)) {
                evaluate(now, null);
            }
        }

        lastObservedHealth = current;
    }

    private void evaluate(long now, PacketReceiveEvent event) {
        boolean packetBurst = GrimAPI.INSTANCE.getIntegrityCorrelationManager().hasRecentWithin(
                player.uuid,
                IntegritySignal.PACKET_BURST,
                TimeUnit.NANOSECONDS.toMillis(abuseWindowNanos)
        );

        if (lastPackets250 < minimumPackets250 && !packetBurst) return;

        double confidence = GrimAPI.INSTANCE.getLagProtectionManager().heuristicConfidence(player);
        if (confidence < minimumConfidence) return;

        buffer += confidence * (packetBurst ? 1.25D : 1.0D);
        if (buffer < flagBuffer || now - lastFlagNanos < flagCooldownNanos) return;

        boolean blocked = event != null && event.isCancelled();
        if (event != null
                && !blocked
                && preventEnabled
                && lastAheadMs >= preventAheadMs
                && shouldModifyPackets()) {
            event.setCancelled(true);
            player.onPacketCancel();
            blocked = true;
        }

        double correlation = GrimAPI.INSTANCE.getIntegrityCorrelationManager()
                .record(player.uuid, IntegritySignal.REGEN_PACKET);

        flag("ahead=" + lastAheadMs + "ms"
                + " packets250=" + lastPackets250
                + " heal=" + String.format(Locale.ROOT, "%.2f", lastHealAmount)
                + " burst=" + packetBurst
                + " blocked=" + blocked
                + " corr=" + String.format(Locale.ROOT, "%.2f", correlation));

        lastFlagNanos = now;
        buffer *= 0.35D;
    }

    private void addMovement(long now) {
        movementTimes[ringIndex] = now;
        ringIndex = (ringIndex + 1) % RING_SIZE;
        if (ringCount < RING_SIZE) ringCount++;
    }

    private int countSince(long minimumNanos) {
        int count = 0;
        for (int i = 0; i < ringCount; i++) {
            int index = ringIndex - 1 - i;
            if (index < 0) index += RING_SIZE;
            long value = movementTimes[index];
            if (value < minimumNanos) break;
            count++;
        }
        return count;
    }

    private static boolean isRecent(long timestamp, long window, long now) {
        return timestamp > 0L && now >= timestamp && now - timestamp <= window;
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        super.onReload(config);
        enabled = config.getBooleanElse("regen-guard.enabled", true);
        preventEnabled = config.getBooleanElse("regen-guard.prevent.enabled", true);
        healWindowNanos = TimeUnit.MILLISECONDS.toNanos(clamp(
                config.getLongElse("regen-guard.heal-correlation-window-ms", 1500L), 250L, 5000L));
        abuseWindowNanos = TimeUnit.MILLISECONDS.toNanos(clamp(
                config.getLongElse("regen-guard.packet-abuse-window-ms", 1200L), 250L, 5000L));
        flagCooldownNanos = TimeUnit.MILLISECONDS.toNanos(clamp(
                config.getLongElse("regen-guard.flag-cooldown-ms", 900L), 250L, 30_000L));
        minimumAheadMs = clamp(config.getLongElse("regen-guard.minimum-timer-ahead-ms", 35L), 1L, 1000L);
        preventAheadMs = clamp(config.getLongElse("regen-guard.prevent.minimum-timer-ahead-ms", 80L), 1L, 2000L);
        minimumPackets250 = (int) clamp(config.getLongElse("regen-guard.minimum-packets-250ms", 7L), 2L, 40L);
        minimumHealAmount = Math.max(0.01D, config.getDoubleElse("regen-guard.minimum-heal-amount", 0.25D));
        minimumConfidence = clamp(config.getDoubleElse("regen-guard.minimum-confidence", 0.60D), 0.0D, 1.0D);
        flagBuffer = Math.max(1.0D, config.getDoubleElse("regen-guard.flag-buffer", 2.0D));
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
