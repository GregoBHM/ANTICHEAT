package ac.grim.grimac.checks.impl.groundspoof;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.manager.integrity.IntegritySignal;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.predictionengine.GhostBlockDetector;
import ac.grim.grimac.utils.collisions.datatypes.SimpleCollisionBox;
import ac.grim.grimac.utils.nmsutil.Collisions;
import ac.grim.grimac.utils.nmsutil.GetBoundingBox;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;

import java.util.ArrayList;
import java.util.List;

// Catches NoFalls for LOOK and GROUND packets
// This check runs AFTER the predictions
@CheckData(name = "NoFall", stableKey = "grim.groundspoof.no_fall",
        description = "Sent an on-ground packet while not colliding with the ground",
        setback = -1)
public class NoFall extends Check implements PacketReceiveListener {

    public boolean flipPlayerGroundStatus = false;

    public NoFall(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event) {
        if (event.getPacketType() == PacketType.Play.Client.PLAYER_FLYING
                || event.getPacketType() == PacketType.Play.Client.PLAYER_ROTATION) {
            if (player.getSetbackTeleportUtil().insideUnloadedChunk()) return;
            if (player.getSetbackTeleportUtil().blockOffsets) return;

            WrapperPlayClientPlayerFlying wrapper = new WrapperPlayClientPlayerFlying(event);

            if (wrapper.isOnGround() && !wrapper.hasPositionChanged()) {
                if (!isNearGround(true)) {
                    if (!GhostBlockDetector.isGhostBlock(player)) {
                        double correlation = GrimAPI.INSTANCE.getIntegrityCorrelationManager()
                                .record(player.uuid, IntegritySignal.NO_FALL);

                        // v22: NoFall removes the advantage by correcting the packet
                        // state. It keeps VL/punishments, but does not teleport the
                        // player merely because the client lied about onGround.
                        flag("corr=" + String.format(java.util.Locale.ROOT, "%.2f", correlation));
                    }

                    if (shouldModifyPackets()) {
                        wrapper.setOnGround(false);
                        event.markForReEncode(true);
                    }
                }
            }
        }

        if (WrapperPlayClientPlayerFlying.isFlying(event.getPacketType())) {
            WrapperPlayClientPlayerFlying wrapper = new WrapperPlayClientPlayerFlying(event);

            if (flipPlayerGroundStatus) {
                flipPlayerGroundStatus = false;
                if (shouldModifyPackets()) {
                    wrapper.setOnGround(!wrapper.isOnGround());
                    event.markForReEncode(true);
                }
            }

            if (player.packetStateData.lastPacketWasTeleport) {
                if (shouldModifyPackets()) {
                    wrapper.setOnGround(false);
                    event.markForReEncode(true);
                }
            }
        }
    }

    private boolean isNearGround(boolean onGround) {
        if (onGround) {
            SimpleCollisionBox feetBB = GetBoundingBox.getBoundingBoxFromPosAndSize(
                    player, player.x, player.y, player.z, 0.6f, 0.001f
            );
            feetBB.expand(player.getMovementThreshold());
            return checkForBoxes(feetBB);
        }
        return true;
    }

    private boolean checkForBoxes(SimpleCollisionBox playerBB) {
        List<SimpleCollisionBox> boxes = new ArrayList<>();
        Collisions.getCollisionBoxes(player, playerBB, boxes, false);

        for (SimpleCollisionBox box : boxes) {
            if (playerBB.collidesVertically(box)) {
                return true;
            }
        }

        return player.compensatedWorld.isNearHardEntity(playerBB.copy().expand(4));
    }
}
