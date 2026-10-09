package ac.grim.grimac.checks.impl.integrity;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.impl.timer.ConnectionStall;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.manager.integrity.IntegritySignal;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.TimeUnit;

/** Attack bursts only become evidence when paired with a confirmed stall/burst; high CPS alone is not enough. */
@CheckData(name = "AttackBurst", alternativeName = "Interact", stableKey = "grim.combat.attack_burst",
        description = "Released a burst of attacks correlated with packet stalling", setback = -1, decay = 0.20)
public final class AttackFrequency extends Check implements PacketReceiveListener {
    private static final int RING_SIZE = 64;
    private final long[] attacks = new long[RING_SIZE];
    private int index;
    private int count;
    private long lastFlagNanos;

    private boolean enabled;
    private int burstAttacks;
    private long burstWindowNanos;
    private long flagCooldownNanos;
    private long causalWindowMillis;

    public AttackFrequency(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!enabled || !isAttack(event)) return;
        long now = System.nanoTime();
        attacks[index] = now;
        index = (index + 1) % RING_SIZE;
        if (count < RING_SIZE) count++;

        int recent = countSince(now - burstWindowNanos);
        if (recent < burstAttacks) return;

        boolean stallEvidence = GrimAPI.INSTANCE.getIntegrityCorrelationManager().hasRecentWithin(
                player.uuid, IntegritySignal.SELECTIVE_STALL, causalWindowMillis)
                || GrimAPI.INSTANCE.getIntegrityCorrelationManager().hasRecentWithin(
                player.uuid, IntegritySignal.PACKET_BURST, causalWindowMillis);
        ConnectionStall stall = player.checkManager.get(ConnectionStall.class);
        // Prevention can start before sanction confidence. Never turn a
        // prevention-only short/full-freeze guard into AttackBurst VL.
        if (stall != null && stall.shouldFlagQueuedActions()) stallEvidence = true;
        if (!stallEvidence || GrimAPI.INSTANCE.getLagProtectionManager().heuristicConfidence(player) < 0.45D) return;
        if (now - lastFlagNanos < flagCooldownNanos) return;

        lastFlagNanos = now;
        double corr = GrimAPI.INSTANCE.getIntegrityCorrelationManager().record(player, IntegritySignal.ATTACK_BURST);
        flag("attacks=" + recent + " window=" + TimeUnit.NANOSECONDS.toMillis(burstWindowNanos)
                + "ms corr=" + String.format(java.util.Locale.ROOT, "%.2f", corr));
    }

    private int countSince(long minimumNanos) {
        int result = 0;
        for (int i = 0; i < count; i++) {
            int current = index - 1 - i;
            if (current < 0) current += RING_SIZE;
            if (attacks[current] < minimumNanos) break;
            result++;
        }
        return result;
    }

    private static boolean isAttack(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.ATTACK) return true;
        return event.getPacketType() == PacketType.Play.Client.INTERACT_ENTITY
                && new WrapperPlayClientInteractEntity(event).getAction() == WrapperPlayClientInteractEntity.InteractAction.ATTACK;
    }

    public void reset() {
        index = 0;
        count = 0;
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        super.onReload(config);
        enabled = config.getBooleanElse("combat-frequency.enabled", true);
        burstAttacks = (int) clamp(config.getLongElse("combat-frequency.burst-attacks", 4L), 3L, 20L);
        burstWindowNanos = TimeUnit.MILLISECONDS.toNanos(clamp(config.getLongElse("combat-frequency.burst-window-ms", 350L), 100L, 2000L));
        flagCooldownNanos = TimeUnit.MILLISECONDS.toNanos(clamp(config.getLongElse("combat-frequency.flag-cooldown-ms", 1200L), 250L, 30_000L));
        causalWindowMillis = clamp(config.getLongElse("combat-frequency.causal-window-ms", 1500L), 250L, 10_000L);
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }
}
