package ac.grim.grimac.checks.impl.multiactions;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.manager.integrity.IntegritySignal;
import ac.grim.grimac.manager.integrity.InventoryClickPreventionPolicy;
import ac.grim.grimac.player.GrimPlayer;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.TimeUnit;

@CheckData(name = "MultiActionsC", stableKey = "grim.multiactions.inventory_click_while_moving", description = "Clicked in inventory while moving")
public class MultiActionsC extends Check implements PacketReceiveListener {
    private static final Verbose V = Verbose.of("sprinting={bool}, sneaking={bool}, input={bool}");

    private int legacyMovingClickStreak;
    private long legacyWindowStartedNanos;
    private long legacyLastFlagNanos;
    private long legacyWindowNanos;
    private long legacyFlagCooldownNanos;
    private long legacyCorrelationWindowMillis;
    private int legacyStandaloneClicks;
    private int legacyCorrelatedClicks;

    public MultiActionsC(GrimPlayer player) {
        super(player);
    }

    @Contract(pure = true)
    public static boolean isVerboseSprinting(@NotNull GrimPlayer player) {
        return player.isSprinting && (!player.isSwimming || !player.clientClaimsLastOnGround);
    }

    @Contract(pure = true)
    public static boolean isVerboseSneaking(@NotNull GrimPlayer player) {
        return player.isSneaking && (player.getClientVersion().isOlderThan(ClientVersion.V_1_15) || player.getClientVersion().isNewerThanOrEquals(ClientVersion.V_1_21_9));
    }

    @Contract(pure = true)
    public static boolean isVerboseInput(@NotNull GrimPlayer player) {
        return player.supportsEndTick() && player.packetStateData.knownInput.moving();
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() != PacketType.Play.Client.CLICK_WINDOW) return;
        if (player.serverOpenedInventoryThisTick) return;

        boolean sprinting = isVerboseSprinting(player);
        boolean sneaking = isVerboseSneaking(player);
        boolean input = isVerboseInput(player);
        if (!sprinting && !sneaking && !input) {
            resetLegacyEvidence();
            return;
        }

        if (GrimAPI.INSTANCE.getInteractionContextManager()
                .suppressesInventoryFrequency(player.uuid)) {
            resetLegacyEvidence();
            return;
        }

        if (player.getClientVersion() == ClientVersion.V_1_8) {
            handleLegacyOneEight(sprinting, sneaking, input);
            return;
        }

        boolean accepted = flag(
                V.write(verbose()).bool(sprinting).bool(sneaking).bool(input)
        );

        if (accepted
                && shouldModifyPackets()
                && InventoryClickPreventionPolicy
                .allowDestructiveHeuristicPrevention(player.getClientVersion())) {
            event.setCancelled(true);
            player.onPacketCancel();
        }
    }

    private void handleLegacyOneEight(
            boolean sprinting,
            boolean sneaking,
            boolean input
    ) {
        long now = System.nanoTime();
        if (legacyWindowStartedNanos == 0L
                || now - legacyWindowStartedNanos > legacyWindowNanos) {
            legacyWindowStartedNanos = now;
            legacyMovingClickStreak = 0;
        }

        legacyMovingClickStreak++;

        var correlation = GrimAPI.INSTANCE.getIntegrityCorrelationManager();
        boolean protocolEvidence = correlation.hasRecentWithin(
                player.uuid,
                IntegritySignal.INVENTORY_FREQUENCY,
                legacyCorrelationWindowMillis
        ) || correlation.hasRecentWithin(
                player.uuid,
                IntegritySignal.PACKET_ORDER,
                legacyCorrelationWindowMillis
        );

        int required = protocolEvidence
                ? legacyCorrelatedClicks
                : legacyStandaloneClicks;

        if (legacyMovingClickStreak < required
                || now - legacyLastFlagNanos < legacyFlagCooldownNanos) {
            return;
        }

        legacyLastFlagNanos = now;
        flag(
                V.write(verbose()).bool(sprinting).bool(sneaking).bool(input)
        );

        // Native 1.8 inventory evidence is detection-only. Keep a little
        // history but require a fresh sustained sequence for the next flag.
        legacyMovingClickStreak = Math.max(0, required - 2);
    }

    private void resetLegacyEvidence() {
        legacyMovingClickStreak = 0;
        legacyWindowStartedNanos = 0L;
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        legacyWindowNanos = TimeUnit.MILLISECONDS.toNanos(clamp(
                config.getLongElse("legacy-inventory-move.window-ms", 1500L),
                500L,
                10_000L
        ));
        legacyFlagCooldownNanos = TimeUnit.MILLISECONDS.toNanos(clamp(
                config.getLongElse("legacy-inventory-move.flag-cooldown-ms", 1500L),
                250L,
                30_000L
        ));
        legacyCorrelationWindowMillis = clamp(
                config.getLongElse("legacy-inventory-move.correlation-window-ms", 1500L),
                250L,
                10_000L
        );
        legacyStandaloneClicks = (int) clamp(
                config.getLongElse("legacy-inventory-move.standalone-clicks", 4L),
                3L,
                20L
        );
        legacyCorrelatedClicks = (int) clamp(
                config.getLongElse("legacy-inventory-move.correlated-clicks", 2L),
                2L,
                legacyStandaloneClicks
        );
        resetLegacyEvidence();
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }
}
