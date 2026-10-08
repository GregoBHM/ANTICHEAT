package ac.grim.grimac.checks.impl.velocity;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketSendListener;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;
import ac.grim.grimac.utils.data.Pair;
import ac.grim.grimac.utils.data.VectorData;
import ac.grim.grimac.utils.data.VelocityData;
import ac.grim.grimac.utils.math.Vector3dm;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import org.jetbrains.annotations.NotNull;

import java.util.Deque;
import java.util.LinkedList;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

// We are making a velocity sandwich between two pieces of transaction packets (bread)
@CheckData(name = "AntiKB", stableKey = "grim.velocity.anti_knockback",
        alternativeName = "AntiKnockback", configName = "Knockback",
        description = "Did not take the expected entity knockback", setback = -1, decay = 0.025)
public class KnockbackHandler extends Check implements PacketSendListener, PostPredictionListener {
    private static final Verbose V = Verbose.of("[ignored knockback|o: {offset}]");

    private final Deque<VelocityData> firstBreadMap = new LinkedList<>();
    private final Deque<VelocityData> lastKnockbackKnownTaken = new LinkedList<>();
    private VelocityData firstBreadOnlyKnockback = null;
    private boolean knockbackPointThree = false;

    private double offsetToFlag;
    private double maxAdv;
    private double immediate;
    private double ceiling;
    private double multiplier;
    private double threshold;

    private boolean enforcementEnabled;
    private boolean suppressInSpecialEnvironments;
    private long enforcementCooldownNanos;
    private long enforcementRecoveryNanos;
    private long enforcementEpisodeResetNanos;
    private int enforcementMaxRetries;
    private double enforcementMinimumScale;

    private long lastEnforcementNanos;
    private long suppressCompetingUntilNanos;
    private long enforcementEpisodeStartedNanos;
    private int enforcementRetries;

    public KnockbackHandler(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPacketSend(final PacketSendEvent event) {
        if (event.getPacketType() == PacketType.Play.Server.ENTITY_VELOCITY) {
            WrapperPlayServerEntityVelocity velocity = new WrapperPlayServerEntityVelocity(event);
            int entityId = velocity.getEntityId();

            if (player.compensatedEntities.serverPlayerVehicle != null
                    && entityId != player.compensatedEntities.serverPlayerVehicle) {
                return;
            }
            if (player.compensatedEntities.serverPlayerVehicle == null && entityId != player.entityID) {
                return;
            }

            Vector3d playerVelocity = velocity.getVelocity();

            if (playerVelocity.getY() == -0.04) {
                velocity.setVelocity(playerVelocity.add(new Vector3d(0, 1 / 8000D, 0)));
                playerVelocity = velocity.getVelocity();
                event.markForReEncode(true);
            }

            playerVelocity = VectorPrecisionConverter.convert(player.getClientVersion(), playerVelocity);

            player.sendTransaction();
            addPlayerKnockback(entityId, player.lastTransactionSent.get(),
                    new Vector3dm(playerVelocity.getX(), playerVelocity.getY(), playerVelocity.getZ()));
            event.getTasksAfterSend().add(player::sendTransaction);
        }
    }

    @NotNull
    public Pair<VelocityData, Vector3dm> getFutureKnockback() {
        if (!firstBreadMap.isEmpty()) {
            VelocityData data = firstBreadMap.peek();
            return new Pair<>(data, data != null ? data.vector : null);
        }

        if (!lastKnockbackKnownTaken.isEmpty()) {
            VelocityData data = lastKnockbackKnownTaken.peek();
            return new Pair<>(data, data != null ? data.vector : null);
        }

        if (player.firstBreadKB != null && player.likelyKB == null) {
            VelocityData data = player.firstBreadKB;
            return new Pair<>(data, data.vector.clone());
        } else if (player.likelyKB != null) {
            VelocityData data = player.likelyKB;
            return new Pair<>(data, data.vector.clone());
        }
        return new Pair<>(null, null);
    }

    private void addPlayerKnockback(int entityID, int breadOne, @NotNull Vector3dm knockback) {
        firstBreadMap.add(new VelocityData(entityID, breadOne,
                player.getSetbackTeleportUtil().isSendingSetback, knockback));
    }

    public VelocityData calculateRequiredKB(int entityID, int transaction, boolean isJustTesting) {
        tickKnockback(transaction);

        VelocityData returnLastKB = null;
        for (VelocityData data : lastKnockbackKnownTaken) {
            if (data.entityID == entityID) returnLastKB = data;
        }

        if (!isJustTesting) lastKnockbackKnownTaken.clear();
        return returnLastKB;
    }

    private void tickKnockback(int transactionID) {
        firstBreadOnlyKnockback = null;
        if (firstBreadMap.isEmpty()) return;
        VelocityData data = firstBreadMap.peek();
        while (data != null) {
            if (data.transaction == transactionID) {
                firstBreadOnlyKnockback = new VelocityData(
                        data.entityID, data.transaction, data.isSetback, data.vector);
                break;
            } else if (data.transaction < transactionID) {
                VelocityData velocityData = new VelocityData(
                        data.entityID, data.transaction, data.isSetback, data.vector);

                if (firstBreadOnlyKnockback != null) velocityData.offset = data.offset;
                lastKnockbackKnownTaken.add(velocityData);
                firstBreadOnlyKnockback = null;
                firstBreadMap.poll();
                data = firstBreadMap.peek();
            } else {
                break;
            }
        }
    }

    public void forceExempt() {
        if (player.firstBreadKB != null) player.firstBreadKB.offset = 0;
        if (player.likelyKB != null) player.likelyKB.offset = 0;
    }

    public void setPointThree(boolean isPointThree) {
        knockbackPointThree = knockbackPointThree || isPointThree;
    }

    public boolean isKnockbackPointThree() {
        return knockbackPointThree;
    }

    public void handlePredictionAnalysis(double offset) {
        if (player.firstBreadKB != null) {
            player.firstBreadKB.offset = Math.min(player.firstBreadKB.offset, offset);
        }
        if (player.likelyKB != null) {
            player.likelyKB.offset = Math.min(player.likelyKB.offset, offset);
        }
    }

    @Override
    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        double offset = predictionComplete.getOffset();
        if (!predictionComplete.isChecked() || predictionComplete.getData().isTeleport()) {
            forceExempt();
            return;
        }

        boolean wasZero = knockbackPointThree;
        knockbackPointThree = false;

        if (player.likelyKB == null && player.firstBreadKB == null) return;

        if (player.predictedVelocity.isFirstBreadKb()) {
            firstBreadOnlyKnockback = null;
            firstBreadMap.poll();
        }

        if (wasZero || player.predictedVelocity.isKnockback()) {
            if (player.firstBreadKB != null) {
                player.firstBreadKB.offset = Math.min(player.firstBreadKB.offset, offset);
            }
            if (player.likelyKB != null) {
                player.likelyKB.offset = Math.min(player.likelyKB.offset, offset);
            }
        }

        if (player.likelyKB != null) {
            if (player.likelyKB.offset > offsetToFlag) {
                threshold = Math.min(threshold + player.likelyKB.offset, ceiling);

                boolean ignored = player.likelyKB.offset == Integer.MAX_VALUE;
                boolean shouldEnforce = player.likelyKB.isSetback
                        || player.likelyKB.offset >= immediate
                        || threshold >= maxAdv;

                boolean enforced = false;
                if (shouldEnforce) {
                    enforced = enforceVelocity(player.likelyKB, ignored);
                }

                String alertText = "offset=" + formatOffset(player.likelyKB.offset)
                        + " expected=" + String.format(Locale.ROOT, "%.4f", Math.sqrt(player.likelyKB.vector.lengthSquared()))
                        + " enforced=" + enforced;

                if (!player.likelyKB.isSetback) {
                    if (!flag(V.write(verbose()).bool(ignored).f64(player.likelyKB.offset), () -> alertText)) {
                        reward();
                    }
                }
            } else if (threshold > 0.05) {
                threshold *= multiplier;
                if (System.nanoTime() - lastEnforcementNanos > enforcementEpisodeResetNanos) {
                    enforcementRetries = 0;
                    enforcementEpisodeStartedNanos = 0L;
                }
            }
        }
    }

    private boolean enforceVelocity(VelocityData data, boolean ignored) {
        if (!enforcementEnabled || !shouldModifyPackets()) return false;
        if (data == null || data.vector == null || data.vector.lengthSquared() <= 1.0E-8D) return false;

        if (suppressInSpecialEnvironments
                && GrimAPI.INSTANCE.getEnvironmentContextManager().isVelocityUncertainEnvironment(player)) {
            return false;
        }

        long now = System.nanoTime();
        if (now - lastEnforcementNanos < enforcementCooldownNanos) return false;

        if (enforcementEpisodeStartedNanos == 0L
                || now - enforcementEpisodeStartedNanos > enforcementEpisodeResetNanos) {
            enforcementEpisodeStartedNanos = now;
            enforcementRetries = 0;
        }

        if (enforcementRetries >= enforcementMaxRetries) return false;

        double magnitude = Math.sqrt(data.vector.lengthSquared());
        double scale = ignored ? 1.0D : clamp(data.offset / Math.max(0.001D, magnitude),
                enforcementMinimumScale, 1.0D);

        Vector3dm enforced = data.vector.clone().multiply(scale);
        player.user.sendPacket(new WrapperPlayServerEntityVelocity(
                player.entityID,
                new Vector3d(enforced.getX(), enforced.getY(), enforced.getZ())
        ));

        lastEnforcementNanos = now;
        suppressCompetingUntilNanos = now + enforcementRecoveryNanos;
        enforcementRetries++;
        return true;
    }

    public boolean shouldSuppressCompetingMovementSetbacks() {
        return System.nanoTime() < suppressCompetingUntilNanos;
    }

    public boolean shouldIgnoreForPrediction(VectorData data) {
        if (data.isKnockback() && data.isFirstBreadKb()) {
            return player.firstBreadKB.offset > offsetToFlag;
        }
        return false;
    }

    public boolean wouldFlag() {
        return (player.likelyKB != null && player.likelyKB.offset > offsetToFlag)
                || (player.firstBreadKB != null && player.firstBreadKB.offset > offsetToFlag);
    }

    public VelocityData calculateFirstBreadKnockback(int entityID, int transaction) {
        tickKnockback(transaction);
        if (firstBreadOnlyKnockback != null && firstBreadOnlyKnockback.entityID == entityID) {
            return firstBreadOnlyKnockback;
        }
        return null;
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        offsetToFlag = config.getDoubleElse("Knockback.threshold", 0.001);
        maxAdv = config.getDoubleElse("Knockback.max-advantage", 1);
        immediate = config.getDoubleElse("Knockback.immediate-setback-threshold", 0.1);
        multiplier = config.getDoubleElse("Knockback.setback-decay-multiplier", 0.999);
        ceiling = config.getDoubleElse("Knockback.max-ceiling", 4);
        if (maxAdv < 0) maxAdv = Double.MAX_VALUE;
        if (immediate < 0) immediate = Double.MAX_VALUE;

        enforcementEnabled = config.getBooleanElse("Knockback.enforcement.enabled", true);
        suppressInSpecialEnvironments = config.getBooleanElse(
                "Knockback.enforcement.suppress-in-special-environments", true);
        enforcementCooldownNanos = TimeUnit.MILLISECONDS.toNanos(clamp(
                config.getLongElse("Knockback.enforcement.cooldown-ms", 120L), 50L, 5000L));
        enforcementRecoveryNanos = TimeUnit.MILLISECONDS.toNanos(clamp(
                config.getLongElse("Knockback.enforcement.recovery-ms", 350L), 50L, 3000L));
        enforcementEpisodeResetNanos = TimeUnit.MILLISECONDS.toNanos(clamp(
                config.getLongElse("Knockback.enforcement.episode-reset-ms", 1200L), 250L, 10_000L));
        enforcementMaxRetries = (int) clamp(
                config.getLongElse("Knockback.enforcement.max-retries", 2L), 1L, 8L);
        enforcementMinimumScale = clamp(
                config.getDoubleElse("Knockback.enforcement.minimum-reapply-scale", 0.35D), 0.05D, 1.0D);
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
