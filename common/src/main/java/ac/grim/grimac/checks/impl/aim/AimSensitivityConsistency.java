package ac.grim.grimac.checks.impl.aim;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.impl.aim.processor.AimProcessor;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.checks.type.RotationListener;
import ac.grim.grimac.manager.integrity.IntegritySignal;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.RotationUpdate;
import ac.grim.grimac.utils.math.GrimMath;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.TimeUnit;

/**
 * Experimental aim-assist evidence based on the mouse-step model already learned
 * by AimProcessor. It does not flag fast turns, target switches, or accuracy by
 * themselves. Repeated combat rotations must violate the inferred mouse
 * granularity before this check produces evidence.
 */
@CheckData(
        name = "AimSensitivity",
        stableKey = "grim.aim.sensitivity_consistency",
        description = "Repeated combat rotations violated inferred mouse sensitivity steps",
        setback = -1,
        decay = 0.08,
        experimental = true
)
public final class AimSensitivityConsistency extends Check
        implements RotationListener, PacketReceiveListener {

    private int recentInvalidRotations;
    private int combatConfirmations;
    private long lastRotationNanos;
    private long lastFlagNanos;

    private boolean enabled;
    private double minimumYaw;
    private double maximumYaw;
    private double residualThreshold;
    private int requiredRotations;
    private int requiredCombatConfirmations;
    private long attackCorrelationNanos;
    private long flagCooldownNanos;

    public AimSensitivityConsistency(GrimPlayer player) {
        super(player);
    }

    @Override
    public void process(RotationUpdate update) {
        if (!enabled
                || player.packetStateData.lastPacketWasTeleport
                || player.vehicleData.wasVehicleSwitch
                || player.packetStateData.horseInteractCausedForcedRotation
                || player.inVehicle()) {
            decayRotationEvidence();
            return;
        }

        AimProcessor processor =
                player.checkManager.get(AimProcessor.class);

        double mode = processor.modeYaw;
        double sensitivity = processor.sensitivityYaw;
        double deltaYaw = update.deltaYawABS();

        if (!Double.isFinite(mode)
                || mode <= GrimMath.MINIMUM_DIVISOR
                || !Double.isFinite(sensitivity)
                || sensitivity < 0.0D
                || sensitivity > 1.0D
                || deltaYaw < minimumYaw
                || deltaYaw > maximumYaw) {
            decayRotationEvidence();
            return;
        }

        double dots = deltaYaw / mode;
        if (!Double.isFinite(dots)) {
            decayRotationEvidence();
            return;
        }

        double residual = Math.abs(dots - Math.rint(dots));
        if (residual >= residualThreshold) {
            recentInvalidRotations++;
        } else {
            recentInvalidRotations = Math.max(0, recentInvalidRotations - 2);
        }

        lastRotationNanos = System.nanoTime();
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (!enabled || !isAttack(event)) return;

        long now = System.nanoTime();
        boolean recentRotation = lastRotationNanos != 0L
                && now - lastRotationNanos <= attackCorrelationNanos;

        if (recentRotation
                && recentInvalidRotations >= requiredRotations) {
            combatConfirmations++;
        } else {
            combatConfirmations = Math.max(0, combatConfirmations - 1);
        }

        if (combatConfirmations < requiredCombatConfirmations
                || now - lastFlagNanos < flagCooldownNanos) {
            return;
        }

        lastFlagNanos = now;

        // Experimental evidence is intentionally excluded from IntegrityScore:
        // enabling it for observation must not indirectly unlock a sanction in
        // an unrelated check.
        flagComponent(
                "aim.sensitivity",
                "invalidRotations=" + recentInvalidRotations
                        + " combatConfirmations=" + combatConfirmations
        );

        // Do not create a flag loop from one rotation sequence.
        recentInvalidRotations = Math.max(
                0,
                requiredRotations - 2
        );
        combatConfirmations = 0;
    }

    private boolean isAttack(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.ATTACK) {
            return true;
        }

        if (event.getPacketType() != PacketType.Play.Client.INTERACT_ENTITY) {
            return false;
        }

        return new WrapperPlayClientInteractEntity(event).getAction()
                == WrapperPlayClientInteractEntity.InteractAction.ATTACK;
    }

    private void decayRotationEvidence() {
        recentInvalidRotations =
                Math.max(0, recentInvalidRotations - 1);
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        enabled = config.getBooleanElse(
                "aim-assist-evidence.enabled",
                true
        );
        minimumYaw = clamp(
                config.getDoubleElse(
                        "aim-assist-evidence.minimum-yaw",
                        0.20D
                ),
                0.01D,
                10.0D
        );
        maximumYaw = clamp(
                config.getDoubleElse(
                        "aim-assist-evidence.maximum-yaw",
                        45.0D
                ),
                minimumYaw,
                180.0D
        );
        residualThreshold = clamp(
                config.getDoubleElse(
                        "aim-assist-evidence.minimum-step-residual",
                        0.18D
                ),
                0.05D,
                0.49D
        );
        requiredRotations = (int) clamp(
                config.getLongElse(
                        "aim-assist-evidence.required-invalid-rotations",
                        5L
                ),
                3L,
                40L
        );
        requiredCombatConfirmations = (int) clamp(
                config.getLongElse(
                        "aim-assist-evidence.required-combat-confirmations",
                        4L
                ),
                2L,
                20L
        );
        attackCorrelationNanos = TimeUnit.MILLISECONDS.toNanos(clamp(
                config.getLongElse(
                        "aim-assist-evidence.attack-correlation-ms",
                        250L
                ),
                50L,
                1000L
        ));
        flagCooldownNanos = TimeUnit.MILLISECONDS.toNanos(clamp(
                config.getLongElse(
                        "aim-assist-evidence.flag-cooldown-ms",
                        3000L
                ),
                500L,
                30_000L
        ));

        recentInvalidRotations = 0;
        combatConfirmations = 0;
        lastRotationNanos = 0L;
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
