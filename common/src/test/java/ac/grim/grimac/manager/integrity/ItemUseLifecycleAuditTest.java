package ac.grim.grimac.manager.integrity;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemUseLifecycleAuditTest {

    @Test
    void reloadClearsEpisodesWithoutKillingPhysicalUseSession() throws Exception {
        String player = Files.readString(Path.of(
                "src/main/java/ac/grim/grimac/player/GrimPlayer.java"
        ));

        int resetConfig = player.indexOf(
                "resetItemUsageOnItemUse ="
        );
        int checkReload = player.indexOf(
                "checkManager.reload();",
                resetConfig
        );
        String reloadBlock = player.substring(resetConfig, checkReload);

        assertTrue(reloadBlock.contains(
                "detectionEpisodeManager.clearAll()"
        ));
        assertFalse(reloadBlock.contains(
                "itemUseSessionTracker.clear()"
        ));
        assertFalse(reloadBlock.contains(
                "itemUseSessionTracker.end("
        ));
    }

    @Test
    void disconnectClearsPerPlayerSessionsAndEpisodes() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/ac/grim/grimac/utils/anticheat/PlayerDataManager.java"
        ));

        assertTrue(source.contains(
                "grimPlayer.itemUseSessionTracker.clear()"
        ));
        assertTrue(source.contains(
                "grimPlayer.detectionEpisodeManager.clearAll()"
        ));
    }

    @Test
    void deathRespawnAndGameModeClearTransientPhysicalState() throws Exception {
        String source = Files.readString(Path.of(
                "../bukkit/src/main/java/ac/grim/grimac/platform/bukkit/events/CombatIntegrityListener.java"
        ));

        assertTrue(source.contains("ItemUseResetPolicy.Reason.DEATH"));
        assertTrue(source.contains("ItemUseResetPolicy.Reason.RESPAWN"));
        assertTrue(source.contains(
                "ItemUseResetPolicy.Reason.GAMEMODE_CHANGE"
        ));
        assertTrue(source.contains(
                "itemUseSessionTracker.end(reason)"
        ));
    }

    @Test
    void teleportClearsMovementEpisodeButDoesNotForceStopItemUse() throws Exception {
        String source = Files.readString(Path.of(
                "../bukkit/src/main/java/ac/grim/grimac/platform/bukkit/events/CombatIntegrityListener.java"
        ));

        int start = source.indexOf("public void onTeleport(");
        int end = source.indexOf("public void onRespawn(", start);
        String method = source.substring(start, end);

        assertTrue(method.contains("detectionEpisodeManager.clearAll()"));
        assertFalse(method.contains("itemUseSessionTracker.end("));
    }


    @Test
    void pluginDisableClearsSourceScopedIntegrationContexts() throws Exception {
        String source = Files.readString(Path.of(
                "../bukkit/src/main/java/ac/grim/grimac/platform/bukkit/events/CombatIntegrityListener.java"
        ));

        assertTrue(source.contains(
                "getMovementContextManager()"
        ));
        assertTrue(source.contains(
                ".clearSourcePrefix(pluginName)"
        ));
        assertTrue(source.contains(
                "getInteractionContextManager()"
        ));
    }
}
