package ac.grim.grimac.manager.integrity;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfirmedSanctionEpisodesAuditTest {

    private static String source(String relative) throws IOException {
        return Files.readString(
                Path.of("src/main/java/ac/grim/grimac").resolve(relative)
        );
    }

    @Test
    void confirmedEpisodeLedgerIsLongWindowAndNonDestructive() throws IOException {
        String manager = source(
                "manager/integrity/SanctionEpisodeManager.java"
        );

        assertTrue(manager.contains("retention-seconds"));
        assertTrue(manager.contains("7200L"));
        assertTrue(manager.contains("max-episodes-per-family"));
        assertTrue(manager.contains("post-continuity-grace-ms"));
        assertTrue(manager.contains("clean-recovery-predictions"));
        assertTrue(manager.contains("minimum-server-confidence"));
        assertTrue(manager.contains("minimum-player-confidence"));
        assertTrue(manager.contains("minimum-tps"));
        assertFalse(manager.contains("kick "));
        assertFalse(manager.contains("ban "));
        assertFalse(manager.contains("event.setCancelled"));
    }

    @Test
    void episodeTokensAreNamespacedPerConnection() throws IOException {
        String manager = source(
                "manager/integrity/SanctionEpisodeManager.java"
        );

        assertTrue(manager.contains("\"join=\""));
        assertTrue(manager.contains("player.joinTime"));
    }

    @Test
    void everyFreshConnectionStartsInSanctionRecovery() throws IOException {
        String playerData = source("utils/anticheat/PlayerDataManager.java");

        assertTrue(playerData.contains(
                "markContinuityBreak(player.uuid, \"join\")"
        ));
    }

    @Test
    void confirmedHistoryIsNotClearedOnPlayerDisconnect() throws IOException {
        String playerData = source("utils/anticheat/PlayerDataManager.java");

        assertFalse(playerData.contains(
                "getSanctionEpisodeManager().clear(uuid)"
        ));
        assertTrue(playerData.contains(
                "detectionEpisodeManager.clearAll()"
        ));
    }

    @Test
    void bukkitContinuityBreaksGateFutureConfirmations() throws IOException {
        String listener = Files.readString(Path.of(
                "../bukkit/src/main/java/ac/grim/grimac/platform/bukkit/events/MovementContextListener.java"
        ));

        assertTrue(listener.contains("markContinuityBreak("));
        assertTrue(listener.contains("\"teleport:\""));
        assertTrue(listener.contains("\"world-change\""));
        assertTrue(listener.contains("\"respawn\""));
        assertTrue(listener.contains("\"gamemode-change\""));
    }

    @Test
    void checkedPredictionsRecoverEpisodeEligibility() throws IOException {
        String simulation = source(
                "checks/impl/prediction/OffsetHandler.java"
        );

        assertTrue(simulation.contains("noteCheckedPrediction(player.uuid)"));
        assertTrue(simulation.contains("\"simulation-normal\""));
        assertTrue(simulation.contains("normalMaxFlagsPerEpisode"));
        assertTrue(simulation.contains("normalEpisodeMaxLifetimeMillis"));
        assertTrue(simulation.contains(
                "SanctionEpisodeManager.FAMILY_MOVEMENT"
        ));
        assertTrue(simulation.contains("\"simulation.normal\""));
    }

    @Test
    void timerAndTimerLimitAreBoundedByPhysicalEpisodes() throws IOException {
        String timer = source("checks/impl/timer/Timer.java");
        String limit = source("checks/impl/timer/TimerLimit.java");

        assertTrue(timer.contains("resolveSharedEpisode("));
        assertTrue(timer.contains("\"timer-normal\""));
        assertTrue(timer.contains("maxFlagsPerEpisode"));
        assertTrue(timer.contains("episodeMaxLifetimeMillis"));
        assertTrue(timer.contains(
                "SanctionEpisodeManager.FAMILY_TIMER"
        ));

        assertTrue(limit.contains("DetectionEpisodeManager.Decision episode"));
        assertTrue(limit.contains("\"timerlimit.release\""));
        assertTrue(limit.contains("maxFlagsPerEpisode"));
        assertFalse(limit.contains(
                "getSanctionEpisodeManager().confirm("
        ));
    }

    @Test
    void noSlowNormalUsesPhysicalItemUseSessionAsEpisodeIdentity()
            throws IOException {
        String noSlow = source(
                "checks/impl/movement/NoSlow.java"
        );

        assertTrue(noSlow.contains(
                "\"noslow.normal|session=\""
        ));
        assertTrue(noSlow.contains(
                "\"itemuse#\" + session.sessionId()"
        ));
        assertTrue(noSlow.contains("normalMaxFlagsPerEpisode"));
        assertTrue(noSlow.contains(
                "SanctionEpisodeManager.FAMILY_ITEM_USE"
        ));
    }

    @Test
    void groundChecksShareEpisodeButOnlyNoFallConfirmsHistory()
            throws IOException {
        String noFall = source(
                "checks/impl/groundspoof/NoFall.java"
        );
        String groundSpoof = source(
                "checks/impl/prediction/GroundSpoof.java"
        );

        assertTrue(noFall.contains("\"ground-integrity\""));
        assertTrue(groundSpoof.contains("\"ground-integrity\""));
        assertTrue(noFall.contains("episodeMaxLifetimeMillis"));
        assertTrue(groundSpoof.contains("episodeMaxLifetimeMillis"));
        assertTrue(noFall.contains(
                "SanctionEpisodeManager.FAMILY_GROUND"
        ));
        assertFalse(groundSpoof.contains(
                "getSanctionEpisodeManager().confirm("
        ));
    }

    @Test
    void antiKbAndCombatChecksFeedIndependentConfirmedEpisodes()
            throws IOException {
        String knockback = source(
                "checks/impl/velocity/KnockbackHandler.java"
        );
        String reach = source(
                "checks/impl/combat/Reach.java"
        );

        assertTrue(knockback.contains(
                "SanctionEpisodeManager.FAMILY_VELOCITY"
        ));
        assertTrue(knockback.contains(
                "\"kb#tx=\" + player.likelyKB.transaction"
        ));
        assertTrue(reach.contains(
                "SanctionEpisodeManager.FAMILY_COMBAT"
        ));
        assertTrue(reach.contains(
                "\"attack#tx=\""
        ));
    }

    @Test
    void sanctionVlAlsoStopsDuringTeleportRecovery() throws IOException {
        String punishment = source("manager/PunishmentManager.java");

        int method = punishment.indexOf("private boolean sanctionableNow()");
        int next = punishment.indexOf("private static double clamp01", method);
        String block = punishment.substring(method, next);

        assertTrue(block.contains("getSanctionEpisodeManager()"));
        assertTrue(block.contains("isRecoveryBlocked(player.uuid)"));
        assertTrue(block.contains("return false;"));
    }

    @Test
    void newMovementFamiliesUseConfirmedEpisodesNotLegacySanctionVl()
            throws IOException {
        String punishment = source("manager/PunishmentManager.java");

        assertFalse(punishment.contains(
                "case \"grim.prediction.simulation\" ->"
        ));
        assertFalse(punishment.contains(
                "case \"grim.movement.noslow\" ->"
        ));
        assertFalse(punishment.contains(
                "case \"grim.groundspoof.no_fall\" ->"
        ));
        assertFalse(punishment.contains(
                "case \"grim.timer.limit\" -> true"
        ));
        assertFalse(punishment.contains(
                "case \"grim.groundspoof.fake\" -> true"
        ));
    }

    @Test
    void punishmentLayerExposesEpisodeMetricsButDoesNotAutobanByItself()
            throws IOException {
        String punishment = source("manager/PunishmentManager.java");

        assertTrue(punishment.contains(
                "case \"confirmed_episodes_10m\""
        ));
        assertTrue(punishment.contains(
                "case \"confirmed_episodes_2h\", \"confirmed_episodes\""
        ));
        assertTrue(punishment.contains(
                "case \"episode_confidence\""
        ));
        assertTrue(punishment.contains(
                "case \"episode_recovery\""
        ));
        assertTrue(punishment.contains(
                "%confirmed_episodes_10m%"
        ));
        assertTrue(punishment.contains(
                "%confirmed_episodes_2h%"
        ));
        assertTrue(punishment.contains(
                "%episode_recovery_reason%"
        ));
    }

    @Test
    void confirmedEpisodeRetentionIsPurgedFromTheGlobalTick() throws IOException {
        String manager = source(
                "manager/integrity/SanctionEpisodeManager.java"
        );
        String tick = source(
                "manager/tick/impl/ClientVersionSetter.java"
        );

        assertTrue(manager.contains("public void tick()"));
        assertTrue(manager.contains("purgeAll()"));
        assertTrue(tick.contains(
                "getSanctionEpisodeManager().tick()"
        ));
    }
}
