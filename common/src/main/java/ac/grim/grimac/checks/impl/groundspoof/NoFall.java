package ac.grim.grimac.checks.impl.groundspoof;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PacketReceiveListener;
import ac.grim.grimac.checks.impl.timer.ConnectionStall;
import ac.grim.grimac.manager.integrity.DetectionEpisodeManager;
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
import org.jetbrains.annotations.NotNull;

// Catches NoFalls for LOOK and GROUND packets
// This check runs AFTER the predictions
@CheckData(name = "NoFall", stableKey = "grim.groundspoof.no_fall",
        description = "Sent an on-ground packet while not colliding with the ground",
        setback = -1)
public class NoFall extends Check implements PacketReceiveListener {

    public boolean flipPlayerGroundStatus = false;

    private long episodeWindowMillis;
    private long episodeFlagIntervalMillis;
    private int maxFlagsPerEpisode;
    private long episodeMaxLifetimeMillis;

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

            if (!player.packetStateData.lastPacketWasTeleport
                    && wrapper.isOnGround()
                    && !wrapper.hasPositionChanged()) {
                if (!isNearGround(true)) {
                    ConnectionStall blinkOwner =
                            player.checkManager.get(ConnectionStall.class);
                    boolean blinkRecovery = blinkOwner != null
                            && blinkOwner.shouldSuppressMovementSetbacks();

                    if (blinkRecovery) {
                        // ConnectionStall/FallIntegrity own this buffered
                        // recovery. Do not create NoFall evidence or rewrite the
                        // same release packet a second time.
                        return;
                    }

                    if (!GhostBlockDetector.isGhostBlock(player)) {
                        DetectionEpisodeManager.SharedEpisode sharedEpisode =
                                player.detectionEpisodeManager.resolveSharedEpisode(
                                        "ground-integrity",
                                        "airborne",
                                        episodeWindowMillis,
                                        episodeMaxLifetimeMillis
                                );

                        DetectionEpisodeManager.Decision episode =
                                player.detectionEpisodeManager.evaluate(
                                        "nofall.normal",
                                        "nofall|" + sharedEpisode.token(),
                                        episodeWindowMillis,
                                        episodeFlagIntervalMillis,
                                        maxFlagsPerEpisode,
                                        sharedEpisode,
                                        2
                                );

                        if (episode.allowFlag()) {
                            double correlation = GrimAPI.INSTANCE
                                    .getIntegrityCorrelationManager()
                                    .record(player.uuid, IntegritySignal.NO_FALL);

                            flagComponent(
                                    "nofall.normal",
                                    "corr=" + String.format(
                                            java.util.Locale.ROOT,
                                            "%.2f",
                                            correlation
                                    )
                                            + " episode="
                                            + sharedEpisode.token()
                                            + " observations="
                                            + episode.observations(),
                                    () -> GrimAPI.INSTANCE.getSanctionEpisodeManager().confirm(
                                            player,
                                            ac.grim.grimac.manager.integrity.SanctionEpisodeManager.FAMILY_GROUND,
                                            "nofall.normal",
                                            sharedEpisode.token()
                                    )
                            );
                        }
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

    @Override
    public void onReload(@NotNull ConfigManager config) {
        episodeWindowMillis = Math.max(
                500L,
                Math.min(
                        10_000L,
                        config.getLongElse(
                                "NoFall.episode-window-ms",
                                2000L
                        )
                )
        );
        episodeFlagIntervalMillis = Math.max(
                0L,
                Math.min(
                        10_000L,
                        config.getLongElse(
                                "NoFall.episode-flag-interval-ms",
                                750L
                        )
                )
        );
        maxFlagsPerEpisode = (int) Math.max(
                1L,
                Math.min(
                        4L,
                        config.getLongElse(
                                "NoFall.max-flags-per-episode",
                                1L
                        )
                )
        );
        episodeMaxLifetimeMillis = Math.max(
                episodeWindowMillis,
                Math.min(
                        30_000L,
                        config.getLongElse(
                                "NoFall.episode-max-lifetime-ms",
                                4000L
                        )
                )
        );
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
