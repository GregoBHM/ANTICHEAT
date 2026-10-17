package ac.grim.grimac.checks.impl.prediction;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.api.storage.verbose.Verbose;
import ac.grim.grimac.checks.Check;
import ac.grim.grimac.checks.CheckData;
import ac.grim.grimac.checks.type.PostPredictionListener;
import ac.grim.grimac.checks.impl.timer.ConnectionStall;
import ac.grim.grimac.manager.integrity.DetectionEpisodeManager;
import ac.grim.grimac.player.GrimPlayer;
import ac.grim.grimac.utils.anticheat.update.PredictionComplete;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.protocol.player.GameMode;
import org.jetbrains.annotations.NotNull;

@CheckData(name = "GroundSpoof", stableKey = "grim.groundspoof.fake", description = "Claimed to be on ground when predicted otherwise", setback = -1, decay = 0.01)
public class GroundSpoof extends Check implements PostPredictionListener {
    private static final Verbose V = Verbose.of("claimed {bool}");

    private boolean quarantineUnsafeGround;
    private long episodeWindowMillis;
    private long episodeFlagIntervalMillis;
    private int maxFlagsPerEpisode;

    public GroundSpoof(GrimPlayer player) {
        super(player);
    }

    @Override
    public void onPredictionComplete(final PredictionComplete predictionComplete) {
        if (PacketEvents.getAPI().getServerManager().getVersion().isNewerThanOrEquals(ServerVersion.V_1_8)
                && player.gamemode == GameMode.SPECTATOR) {
            return;
        }

        if (player.exemptOnGround() || !predictionComplete.isChecked()) {
            return;
        }

        if (player.getSetbackTeleportUtil().blockOffsets) {
            return;
        }

        if (player.packetStateData.lastPacketWasTeleport) {
            return;
        }

        boolean claimed = player.clientClaimsLastOnGround;
        if (claimed != player.onGround) {
            ConnectionStall blinkOwner = player.checkManager.get(ConnectionStall.class);
            boolean blinkRecovery = blinkOwner != null && blinkOwner.shouldSuppressMovementSetbacks();

            // GroundSpoof corroborates one airborne physical episode.
            // It never creates long-window sanction history by itself.
            DetectionEpisodeManager.SharedEpisode sharedEpisode =
                    player.detectionEpisodeManager.resolveSharedEpisode(
                            "ground-integrity",
                            "airborne",
                            episodeWindowMillis
                    );
            DetectionEpisodeManager.Decision episode =
                    player.detectionEpisodeManager.evaluate(
                            "groundspoof.normal",
                            "groundspoof|" + sharedEpisode.token(),
                            episodeWindowMillis,
                            episodeFlagIntervalMillis,
                            maxFlagsPerEpisode,
                            sharedEpisode,
                            2
                    );

            boolean accepted = episode.allowFlag()
                    && flagComponent(
                    "groundspoof.normal",
                    V.write(verbose()).bool(claimed),
                    () -> "claimed=" + claimed
                            + " episode=" + sharedEpisode.token()
                            + " observations=" + episode.observations()
            );

            boolean specialEnvironment = GrimAPI.INSTANCE.getEnvironmentContextManager()
                    .isSpecialMovementEnvironment(player);

            if (accepted && quarantineUnsafeGround && !blinkRecovery && !specialEnvironment) {
                predictionComplete.setSafePositionUpdateBlocked(true);
            }

            player.checkManager.getNoFall().flipPlayerGroundStatus = true;
        }
    }

    @Override
    public void onReload(@NotNull ConfigManager config) {
        try {
            Object raw = config.get("movement-enforcement.ground-spoof-quarantine");
            quarantineUnsafeGround = raw instanceof Boolean value && value;
        } catch (RuntimeException ex) {
            quarantineUnsafeGround = false;
        }

        episodeWindowMillis = Math.max(
                500L,
                Math.min(
                        10_000L,
                        config.getLongElse(
                                "GroundSpoof.episode-window-ms",
                                2000L
                        )
                )
        );
        episodeFlagIntervalMillis = Math.max(
                0L,
                Math.min(
                        10_000L,
                        config.getLongElse(
                                "GroundSpoof.episode-flag-interval-ms",
                                750L
                        )
                )
        );
        maxFlagsPerEpisode = (int) Math.max(
                1L,
                Math.min(
                        4L,
                        config.getLongElse(
                                "GroundSpoof.max-flags-per-episode",
                                1L
                        )
                )
        );
    }
}
