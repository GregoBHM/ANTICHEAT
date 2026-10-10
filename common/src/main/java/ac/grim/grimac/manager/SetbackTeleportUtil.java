package ac.grim.grimac.manager;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.event.events.GrimPlayerSetbackEvent;
import ac.grim.grimac.api.event.events.GrimTeleportEvent;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.impl.timer.ConnectionStall;
import ac.grim.grimac.checks.impl.velocity.KnockbackHandler;
import ac.grim.grimac.manager.integrity.IntegritySignal;
import ac.grim.grimac.manager.integrity.MovementCorrectionCoordinator;
import ac.grim.grimac.checks.GrimProcessor;
import ac.grim.grimac.checks.impl.badpackets.BadPacketsN;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.platform.api.entity.GrimEntity;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.predictionengine.predictions.PredictionEngine;
import ac.grim.grimac.predictionengine.predictions.PredictionEngineElytra;
import ac.grim.grimac.predictionengine.predictions.PredictionEngineNormal;
import ac.grim.grimac.predictionengine.predictions.PredictionEngineWater;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;
import ac.grim.grimac.utils.chunks.Column;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.data.*;
import ac.grim.grimac.utils.math.GrimMath;
import ac.grim.grimac.utils.math.Location;
import ac.grim.grimac.utils.math.Vector3dm;
import ac.grim.grimac.utils.math.VectorUtils;
import ac.grim.grimac.utils.nmsutil.BlockProperties;
import ac.grim.grimac.utils.nmsutil.Collisions;
import ac.grim.grimac.utils.nmsutil.GetBoundingBox;
import ac.grim.grimac.utils.nmsutil.ReachUtils;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import com.github.retrooper.packetevents.protocol.teleport.RelativeFlag;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerAttachEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityTeleport;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityVelocity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerPositionAndLook;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.HashSet;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;

public class SetbackTeleportUtil extends GrimProcessor implements PostPredictionListener {
    public final ConcurrentLinkedQueue<TeleportData> pendingTeleports = new ConcurrentLinkedQueue<>();
    private final Random random = new Random();
    private static final GrimTeleportEvent.Channel TELEPORT_CHANNEL = GrimAPI.INSTANCE.getEventBus().get(GrimTeleportEvent.class);
    private static final GrimPlayerSetbackEvent.Channel PLAYER_SETBACK_CHANNEL = GrimAPI.INSTANCE.getEventBus().get(GrimPlayerSetbackEvent.class);

    public boolean hasAcceptedSpawnTeleport = false;
    public boolean blockOffsets = false;
    public SetbackPosWithVector lastKnownGoodPosition;
    public boolean isSendingSetback = false;
    public int cheatVehicleInterpolationDelay = 0;

    private SetBackData requiredSetBack = null;
    private long lastWorldResync = 0;
    private final MovementCorrectionCoordinator correctionCoordinator =
            new MovementCorrectionCoordinator();

    public SetbackTeleportUtil(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        Vector3dm afterTickFriction = player.clientVelocity.clone();

        if (predictionComplete.getData().getSetback() != null) {
            if (cheatVehicleInterpolationDelay > 0) cheatVehicleInterpolationDelay = 10;
            lastKnownGoodPosition = new SetbackPosWithVector(
                    new Vector3d(player.x, player.y, player.z),
                    afterTickFriction
            );
        } else if (!predictionComplete.isSafePositionUpdateBlocked()
                && (requiredSetBack == null || requiredSetBack.isComplete())) {
            cheatVehicleInterpolationDelay--;
            lastKnownGoodPosition = new SetbackPosWithVector(
                    new Vector3d(player.x, player.y, player.z),
                    afterTickFriction
            );
        }

        if (requiredSetBack != null) requiredSetBack.tick();
    }

    public void executeForceResync() {
        executeForceResync(null);
    }

    public void executeForceResync(@Nullable Check source) {
        if (player.gamemode == GameMode.SPECTATOR || player.disableGrim)
            return;
        if (lastKnownGoodPosition == null) return;

        // Routine ground/0.03/ghost resyncs must not fight legitimate
        // special movement physics. Explicit check sources are diagnostic only;
        // they do not bypass this safety policy.
        if (GrimAPI.INSTANCE.getEnvironmentContextManager()
                .shouldSuppressRoutineForceResync(player)) {
            return;
        }

        if (applyCorrection(
                source,
                correctionPriority(source, false),
                true,
                true
        )) {
            emitCorrectionDiagnostic(source, "resync");
        }
    }

    public void executeNonSimulatingForceResync() {
        executeNonSimulatingForceResync(null);
    }

    public void executeNonSimulatingForceResync(@Nullable Check source) {
        if (player.gamemode == GameMode.SPECTATOR || player.disableGrim)
            return;
        if (lastKnownGoodPosition == null) return;
        if (applyCorrection(
                source,
                correctionPriority(source, false),
                false,
                true
        )) {
            emitCorrectionDiagnostic(source, "resync");
        }
    }

    public void executeNonSimulatingSetback() {
        executeNonSimulatingSetback(null);
    }

    public void executeNonSimulatingSetback(@Nullable Check source) {
        if (player.gamemode == GameMode.SPECTATOR || player.disableGrim)
            return;
        if (lastKnownGoodPosition == null) return;
        if (applyCorrection(
                source,
                correctionPriority(source, false),
                false,
                false
        )) {
            emitCorrectionDiagnostic(source, "connection");
        }
    }

    public boolean executeViolationSetback() {
        return executeViolationSetback(null);
    }

    public boolean executeViolationSetback(@Nullable Check source) {
        if (isExempt()) return false;
        if (!applyCorrection(
                source,
                correctionPriority(source, true),
                true,
                false
        )) {
            return false;
        }
        emitCorrectionDiagnostic(source, "prediction");
        return true;
    }

    private MovementCorrectionCoordinator.Priority correctionPriority(
            @Nullable Check source,
            boolean violation
    ) {
        if (violation && source == null) {
            return MovementCorrectionCoordinator.Priority.AUTHORITATIVE;
        }

        if (source != null && source.getStableKey() != null) {
            String stableKey = source.getStableKey();

            if (stableKey.startsWith("grim.crash.")
                    || stableKey.equals("grim.exploit.cancelled_block_climb")) {
                return MovementCorrectionCoordinator.Priority.AUTHORITATIVE;
            }
        }

        return source == null
                ? MovementCorrectionCoordinator.Priority.ROUTINE
                : MovementCorrectionCoordinator.Priority.CHECK;
    }

    private boolean applyCorrection(
            @Nullable Check source,
            MovementCorrectionCoordinator.Priority priority,
            boolean simulateNextTickPosition,
            boolean isResync
    ) {
        ConnectionStall stall = player.checkManager.get(ConnectionStall.class);
        long connectionEpisodeId = stall == null
                ? 0L
                : stall.getCorrectionEpisodeId();

        KnockbackHandler knockback = player.checkManager.get(KnockbackHandler.class);
        boolean velocityOwnsRecovery = knockback != null
                && knockback.shouldSuppressCompetingMovementSetbacks();

        boolean sourceIsConnectionStall = source instanceof ConnectionStall;
        long now = System.nanoTime();

        if (!correctionCoordinator.canApply(
                priority,
                sourceIsConnectionStall,
                connectionEpisodeId,
                velocityOwnsRecovery,
                isPendingSetback(),
                now
        )) {
            return false;
        }

        if (!blockMovementsUntilResync(simulateNextTickPosition, isResync)) {
            return false;
        }

        correctionCoordinator.markApplied(
                sourceIsConnectionStall,
                connectionEpisodeId
        );
        return true;
    }

    private boolean isExempt() {
        if (lastKnownGoodPosition == null) return true;
        if (player.disableGrim) return true;
        return player.platformPlayer != null && player.noSetbackPermission;
    }

    private void simulateFriction(Vector3dm vector) {
        if (player.wasTouchingWater) {
            PredictionEngineWater.staticVectorEndOfTick(player, vector, 0.8F, player.gravity, true);
        } else if (player.wasTouchingLava) {
            vector.multiply(0.5D);
            if (player.hasGravity)
                vector.add(0.0D, -player.gravity / 4.0D, 0.0D);
        } else if (player.isGliding) {
            PredictionEngineElytra.getElytraMovement(
                    player,
                    vector,
                    ReachUtils.getLook(player, player.yaw, player.pitch)
            ).multiply(player.stuckSpeedMultiplier).multiply(0.99F, 0.98F, 0.99F);
            vector.setY(vector.getY() - 0.05);
        } else {
            PredictionEngineNormal.staticVectorEndOfTick(player, vector);
        }

        vector.multiply(player.stuckSpeedMultiplier);

        new PredictionEngine().applyMovementThreshold(
                player,
                new HashSet<>(Collections.singletonList(
                        new VectorData(vector, VectorData.VectorType.BestVelPicked)
                ))
        );
    }

    private boolean blockMovementsUntilResync(boolean simulateNextTickPosition, boolean isResync) {
        if (requiredSetBack == null) return false;
        if (player.platformPlayer != null && player.noSetbackPermission)
            return false;

        requiredSetBack.setPlugin(false);
        if (isPendingSetback()) return false;

        if (System.currentTimeMillis() - lastWorldResync > 5 * 1000) {
            player.resyncPositions(player.boundingBox.copy().expand(1));
            lastWorldResync = System.currentTimeMillis();
        }

        Vector3dm clientVel = lastKnownGoodPosition.vector.clone();

        Pair<VelocityData, Vector3dm> futureKb = player.checkManager.getKnockbackHandler().getFutureKnockback();
        VelocityData futureExplosion = player.checkManager.getExplosionHandler().getFutureExplosion();

        if (futureKb.first() != null && !futureKb.first().isSetback) {
            clientVel = futureKb.second();
        }

        if (futureExplosion != null && (futureKb.first() == null
                || (futureKb.first().transaction < futureExplosion.transaction && !futureKb.first().isSetback))) {
            clientVel.add(futureExplosion.vector);
        }

        Vector3d position = lastKnownGoodPosition.pos;

        SimpleCollisionBox oldBB = player.boundingBox;
        player.boundingBox = GetBoundingBox.getPlayerBoundingBox(
                player,
                position.getX(),
                position.getY(),
                position.getZ()
        );

        if (simulateNextTickPosition) {
            Vector3dm collide = Collisions.collide(
                    player,
                    clientVel.getX(),
                    clientVel.getY(),
                    clientVel.getZ()
            );

            position = position.withX(position.getX() + collide.getX());
            position = position.withY(position.getY() + collide.getY());

            if (player.getClientVersion().isOlderThan(ClientVersion.V_1_9)) {
                position = position.withY(position.getY() + SimpleCollisionBox.COLLISION_EPSILON);
            }

            position = position.withZ(position.getZ() + collide.getZ());

            if (clientVel.getX() != collide.getX()) {
                clientVel.setX(BlockProperties.getVelocityAfterHorizontalCollision(
                        player,
                        clientVel.getX()
                ));
            }

            if (clientVel.getY() != collide.getY()) {
                clientVel.setY(BlockProperties.getVelocityAfterVerticalCollision(
                        player,
                        clientVel.getY(),
                        collide.getY()
                ));
            }

            if (clientVel.getZ() != collide.getZ()) {
                clientVel.setZ(BlockProperties.getVelocityAfterHorizontalCollision(
                        player,
                        clientVel.getZ()
                ));
            }

            simulateFriction(clientVel);
        }

        player.boundingBox = oldBB;

        if (!hasAcceptedSpawnTeleport || player.isFlying)
            clientVel = null;

        if (isResync) {
            blockOffsets = true;
        }

        SetBackData data = new SetBackData(
                new TeleportData(
                        position,
                        0,
                        0,
                        null,
                        RelativeFlag.YAW.or(RelativeFlag.PITCH),
                        player.lastTransactionSent.get(),
                        0
                ),
                player.yaw,
                player.pitch,
                clientVel,
                player.inVehicle(),
                false
        );

        sendSetback(data);
        return true;
    }

    private void emitCorrectionDiagnostic(@Nullable Check source, String mode) {
        ConnectionStall stall = player.checkManager.get(ConnectionStall.class);

        String environment = GrimAPI.INSTANCE
                .getEnvironmentContextManager()
                .summary(player);

        StringBuilder related = new StringBuilder();
        related.append("related=").append(environment);

        if (stall != null && (stall.shouldSuppressMovementSetbacks()
                || stall.shouldBlockQueuedActions())) {
            related.append(",blink");
        }

        KnockbackHandler knockback = player.checkManager.get(KnockbackHandler.class);
        if (knockback != null && knockback.shouldSuppressCompetingMovementSetbacks()) {
            related.append(",velocity");
        }

        long causalWindow = 1500L;
        if (GrimAPI.INSTANCE.getIntegrityCorrelationManager().hasRecentWithin(
                player.uuid, IntegritySignal.PACKET_BURST, causalWindow)) {
            related.append(",packet_burst");
        }
        if (GrimAPI.INSTANCE.getIntegrityCorrelationManager().hasRecentWithin(
                player.uuid, IntegritySignal.SELECTIVE_STALL, causalWindow)) {
            related.append(",selective_stall");
        }
        if (GrimAPI.INSTANCE.getIntegrityCorrelationManager().hasRecentWithin(
                player.uuid, IntegritySignal.PHASE, causalWindow)) {
            related.append(",phase");
        }
        if (GrimAPI.INSTANCE.getIntegrityCorrelationManager().hasRecentWithin(
                player.uuid, IntegritySignal.NO_FALL, causalWindow)) {
            related.append(",no_fall");
        }

        related.append(" mode=").append(mode);

        String fallback = switch (mode) {
            case "resync" -> "MovementResync";
            case "connection" -> "ConnectionCorrection";
            default -> "MovementCorrection";
        };

        player.punishmentManager.handleCorrectionDiagnostic(
                source,
                fallback,
                related.toString()
        );
    }

    private void sendSetback(SetBackData data) {
        isSendingSetback = true;
        Vector3d position = data.getTeleportData().getLocation();

        try {
            if (player.inVehicle()) {
                int vehicleId = player.getRidingVehicleId();

                if (player.compensatedEntities.serverPlayerVehicle != null) {
                    if (PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_9)) {
                        player.user.sendPacket(new WrapperPlayServerSetPassengers(vehicleId, new int[2]));
                    } else {
                        player.user.sendPacket(new WrapperPlayServerAttachEntity(vehicleId, -1, false));
                    }

                    player.user.sendPacket(
                            new WrapperPlayServerEntityTeleport(
                                    vehicleId,
                                    new Vector3d(position.getX(), position.getY(), position.getZ()),
                                    player.yaw % 360,
                                    0,
                                    false
                            )
                    );

                    player.getSetbackTeleportUtil().cheatVehicleInterpolationDelay = Integer.MAX_VALUE;

                    GrimAPI.INSTANCE.getScheduler().getEntityScheduler().execute(
                            player.platformPlayer,
                            GrimAPI.INSTANCE.getGrimPlugin(),
                            () -> {
                                if (player.platformPlayer != null) {
                                    GrimEntity vehicle = player.platformPlayer.getVehicle();
                                    if (vehicle != null) {
                                        vehicle.eject();
                                    }
                                }
                            },
                            null,
                            0
                    );
                }
            }

            double y = position.getY();

            if (PacketEvents.getAPI().getServerManager().getVersion().isOlderThanOrEquals(ServerVersion.V_1_7_10)) {
                y += 1.62;
            }

            player.sendTransaction();

            int teleportId = random.nextInt() | Integer.MIN_VALUE;
            data.setPlugin(false);
            data.getTeleportData().setTeleportId(teleportId);
            data.getTeleportData().setTransaction(player.lastTransactionSent.get());

            addSentTeleport(
                    new Location(null, position.getX(), y, position.getZ()),
                    null,
                    data.getTeleportData().getTransaction(),
                    RelativeFlag.YAW.or(RelativeFlag.PITCH),
                    false,
                    teleportId
            );

            requiredSetBack = data;

            PacketEvents.getAPI().getProtocolManager().sendPacketSilently(
                    player.user.getChannel(),
                    new WrapperPlayServerPlayerPositionAndLook(
                            position.getX(),
                            position.getY(),
                            position.getZ(),
                            0,
                            0,
                            data.getTeleportData().getFlags().getMask(),
                            teleportId,
                            false
                    )
            );

            long now = System.currentTimeMillis();
            TELEPORT_CHANNEL.fire(player, teleportId, now);
            PLAYER_SETBACK_CHANNEL.fire(
                    player,
                    teleportId,
                    position.getX(),
                    position.getY(),
                    position.getZ(),
                    now
            );

            player.sendTransaction();

            if (data.getVelocity() != null && data.getVelocity().lengthSquared() > 0) {
                player.user.sendPacket(
                        new WrapperPlayServerEntityVelocity(
                                player.entityID,
                                new Vector3d(
                                        data.getVelocity().getX(),
                                        data.getVelocity().getY(),
                                        data.getVelocity().getZ()
                                )
                        )
                );
            }
        } finally {
            isSendingSetback = false;
        }
    }

    public TeleportAcceptData checkTeleportQueue(
            double x,
            double y,
            double z,
            float yaw,
            float pitch
    ) {
        return checkTeleportQueue(x, y, z, yaw, pitch, null);
    }

    public TeleportAcceptData checkTeleportQueue(
            double x,
            double y,
            double z,
            float yaw,
            float pitch,
            @Nullable Integer teleportId
    ) {
        TeleportAcceptData teleportData = new TeleportAcceptData();

        TeleportData teleportPos;

        while ((teleportPos = pendingTeleports.peek()) != null) {
            double trueTeleportX =
                    (teleportPos.isRelativeX() ? player.x : 0)
                            + teleportPos.getLocation().getX();

            double trueTeleportY =
                    (teleportPos.isRelativeY() ? player.y : 0)
                            + teleportPos.getLocation().getY();

            double trueTeleportZ =
                    (teleportPos.isRelativeZ() ? player.z : 0)
                            + teleportPos.getLocation().getZ();

            Vector3d clamped = VectorUtils.clampVector(
                    new Vector3d(trueTeleportX, trueTeleportY, trueTeleportZ)
            );

            double threshold = teleportPos.isRelativePos()
                    ? player.getMovementThreshold()
                    : 0;

            boolean closeEnoughY =
                    Math.abs(clamped.getY() - y) <= 1e-7 + threshold;

            boolean correctRotations =
                    (yaw == teleportPos.getYaw() || teleportPos.isRelativeYaw())
                            && (pitch == teleportPos.getPitch() || teleportPos.isRelativePitch());

            if ((teleportId == null || teleportId.equals(teleportPos.getTeleportId()))
                    && player.lastTransactionReceived.get() == teleportPos.getTransaction()
                    && Math.abs(clamped.getX() - x) <= threshold
                    && closeEnoughY
                    && Math.abs(clamped.getZ() - z) <= threshold
                    && correctRotations) {

                pendingTeleports.poll();
                hasAcceptedSpawnTeleport = true;
                blockOffsets = false;

                if (requiredSetBack != null
                        && requiredSetBack.getTeleportData().getTransaction()
                        == teleportPos.getTransaction()) {

                    teleportData.setSetback(requiredSetBack);
                    requiredSetBack.setComplete(true);

                    if (!requiredSetBack.isPlugin()) {
                        correctionCoordinator.acknowledge(System.nanoTime());
                    }
                }

                teleportData.setTeleportData(teleportPos);
                teleportData.setTeleport(true);
                break;

            } else if (player.lastTransactionReceived.get() > teleportPos.getTransaction()) {
                player.checkManager.get(BadPacketsN.class).flag();
                pendingTeleports.poll();
                requiredSetBack.setPlugin(false);

                if (pendingTeleports.isEmpty()) {
                    sendSetback(requiredSetBack);
                }

                continue;
            }

            break;
        }

        return teleportData;
    }

    public boolean checkVehicleTeleportQueue(double x, double y, double z) {
        int lastTransaction = player.lastTransactionReceived.get();

        while (true) {
            IntToObjectPair<Vector3d> teleportPos =
                    player.vehicleData.vehicleTeleports.peek();

            if (teleportPos == null) break;

            if (lastTransaction < teleportPos.first()) {
                break;
            }

            Vector3d position = teleportPos.second();

            if (position.getX() == x
                    && position.getY() == y
                    && position.getZ() == z) {

                player.vehicleData.vehicleTeleports.poll();
                return true;

            } else if (lastTransaction > teleportPos.first() + 1) {
                player.vehicleData.vehicleTeleports.poll();
                continue;
            }

            break;
        }

        return false;
    }

    public boolean shouldBlockMovement() {
        return insideUnloadedChunk()
                || blockOffsets
                || (requiredSetBack != null && !requiredSetBack.isComplete());
    }

    private boolean isPendingSetback() {
        if (requiredSetBack != null
                && (requiredSetBack.getTeleportData().isRelativeX()
                || requiredSetBack.getTeleportData().isRelativeY()
                || requiredSetBack.getTeleportData().isRelativeZ())) {
            return false;
        }

        return requiredSetBack != null && !requiredSetBack.isComplete();
    }

    public boolean insideUnloadedChunk() {
        Column column = player.compensatedWorld.getChunk(
                GrimMath.floor(player.x) >> 4,
                GrimMath.floor(player.z) >> 4
        );

        return !player.disableGrim
                && (column == null
                || column.transaction() >= player.lastTransactionReceived.get()
                || !player.getSetbackTeleportUtil().hasAcceptedSpawnTeleport);
    }

    public void addSentTeleport(
            Location position,
            @Nullable Vector3d velocity,
            int transaction,
            RelativeFlag flags,
            boolean plugin,
            int teleportId
    ) {
        if (player.getClientVersion().isOlderThan(ClientVersion.V_1_21_2)) {
            velocity = null;
        }

        TeleportData data = new TeleportData(
                new Vector3d(position.getX(), position.getY(), position.getZ()),
                position.getYaw(),
                position.getPitch(),
                velocity,
                flags,
                transaction,
                teleportId
        );

        pendingTeleports.add(data);

        Vector3d safePosition = new Vector3d(
                position.getX(),
                position.getY(),
                position.getZ()
        );

        if (flags.has(RelativeFlag.X)) {
            safePosition = safePosition.withX(
                    safePosition.getX() + lastKnownGoodPosition.pos.getX()
            );
        }

        if (flags.has(RelativeFlag.Y)) {
            safePosition = safePosition.withY(
                    safePosition.getY() + lastKnownGoodPosition.pos.getY()
            );
        }

        if (flags.has(RelativeFlag.Z)) {
            safePosition = safePosition.withZ(
                    safePosition.getZ() + lastKnownGoodPosition.pos.getZ()
            );
        }

        data = new TeleportData(
                safePosition,
                0,
                0,
                velocity,
                RelativeFlag.YAW.or(RelativeFlag.PITCH),
                transaction,
                teleportId
        );

        requiredSetBack = new SetBackData(
                data,
                player.yaw,
                player.pitch,
                null,
                false,
                plugin
        );

        this.lastKnownGoodPosition =
                new SetbackPosWithVector(safePosition, new Vector3dm());
    }

    public SetBackData getRequiredSetBack() {
        return requiredSetBack;
    }

    public static class SetbackPosWithVector {
        private final Vector3d pos;
        private Vector3dm vector;

        public SetbackPosWithVector(Vector3d pos, Vector3dm vector) {
            this.pos = pos;
            this.vector = vector;
        }

        public Vector3d getPos() {
            return pos;
        }

        public Vector3dm getVector() {
            return vector;
        }

        public void setVector(Vector3dm vector) {
            this.vector = vector;
        }
    }
}
