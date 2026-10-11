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

    @Test
    void teleportAndWorldChangeDoNotClearPhysicalConsumeSessionIndirectly()
            throws Exception {
        String source = Files.readString(Path.of(
                "../bukkit/src/main/java/ac/grim/grimac/platform/bukkit/events/InteractionIntegrityListener.java"
        ));

        int teleportStart = source.indexOf("public void onTeleport(");
        int worldStart = source.indexOf("public void onWorldChange(", teleportStart);
        int gameModeStart = source.indexOf("public void onGameMode(", worldStart);

        String teleport = source.substring(teleportStart, worldStart);
        String worldChange = source.substring(worldStart, gameModeStart);

        assertTrue(teleport.contains("clearLifecycle(event.getPlayer().getUniqueId())"));
        assertTrue(worldChange.contains("clearLifecycle(event.getPlayer().getUniqueId())"));
        assertFalse(teleport.contains("ConsumeTiming.class).reset()"));
        assertFalse(worldChange.contains("ConsumeTiming.class).reset()"));
    }

    @Test
    void consumeDecisionsArePlayerOwnedAndReturnToPlayerEventLoop()
            throws Exception {
        String source = Files.readString(Path.of(
                "../bukkit/src/main/java/ac/grim/grimac/platform/bukkit/events/InteractionIntegrityListener.java"
        ));

        assertTrue(source.contains(
                "grim.runSafely(() -> consume.applyServerConsumeDecision(decision))"
        ));
        assertTrue(source.contains("public void onQuit(PlayerQuitEvent event)"));
        assertFalse(source.contains("PENDING_CONSUME"));
        assertTrue(source.contains("consume.setPendingDecision(decision)"));
        assertTrue(source.contains("consume.takePendingDecision()"));
        assertTrue(source.contains("suppressesConsumeTiming(uuid)"));
    }

    @Test
    void lifecycleCleanupNeverResetsPhysicalSessionThroughConsumeTiming()
            throws Exception {
        String source = Files.readString(Path.of(
                "../bukkit/src/main/java/ac/grim/grimac/platform/bukkit/events/InteractionIntegrityListener.java"
        ));

        int start = source.indexOf("private static void clearLifecycle(");
        int end = source.indexOf("private static GrimPlayer grim(", start);
        String method = source.substring(start, end);

        assertFalse(method.contains("ConsumeTiming.class"));
        assertFalse(method.contains("itemUseSessionTracker"));
    }

    @Test
    void movementLifecycleCleanupDoesNotDependOnAutoObservationSetting()
            throws Exception {
        String source = Files.readString(Path.of(
                "../bukkit/src/main/java/ac/grim/grimac/platform/bukkit/events/MovementContextListener.java"
        ));

        int teleportStart = source.indexOf("public void onTeleport(");
        int worldStart = source.indexOf("public void onWorldChange(", teleportStart);
        int gameModeStart = source.indexOf("public void onGameModeChange(", worldStart);
        int cancelledMoveStart = source.indexOf("public void onCancelledMove(", gameModeStart);

        String teleport = source.substring(teleportStart, worldStart);
        String world = source.substring(worldStart, gameModeStart);
        String gameMode = source.substring(gameModeStart, cancelledMoveStart);

        assertTrue(teleport.indexOf("contexts.clear(") < teleport.indexOf("if (!enabled()) return"));
        assertTrue(world.indexOf("contexts.clear(") < world.indexOf("if (!enabled()) return"));
        assertTrue(gameMode.contains(".clear(event.getPlayer().getUniqueId())"));
        assertFalse(gameMode.contains("if (!enabled()) return"));
    }

    @Test
    void consumeTimingOwnsPendingDecisionWithoutGlobalUuidMap()
            throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/ac/grim/grimac/checks/impl/integrity/ConsumeTiming.java"
        ));

        assertTrue(source.contains("private ConsumeDecision pendingDecision;"));
        assertTrue(source.contains("setPendingDecision("));
        assertTrue(source.contains("takePendingDecision()"));
        assertTrue(source.contains("clearPendingDecision()"));
    }

    @Test
    void lateTrustedConsumeContextIsCheckedBeforeDecisionApplication()
            throws Exception {
        String source = Files.readString(Path.of(
                "../bukkit/src/main/java/ac/grim/grimac/platform/bukkit/events/InteractionIntegrityListener.java"
        ));

        int monitor = source.indexOf("public void onConsumeMonitor(");
        int nextHandler = source.indexOf("@EventHandler", monitor + 1);
        String method = source.substring(monitor, nextHandler);

        int trustedCheck = method.indexOf("suppressesConsumeTiming(uuid)");
        int applyDecision = method.indexOf("applyServerConsumeDecision(decision)");

        assertTrue(trustedCheck >= 0);
        assertTrue(applyDecision > trustedCheck);
    }

    @Test
    void teleportReplacesOldMovementContextsWithObservedTransition()
            throws Exception {
        String source = Files.readString(Path.of(
                "../bukkit/src/main/java/ac/grim/grimac/platform/bukkit/events/MovementContextListener.java"
        ));

        int start = source.indexOf("public void onTeleport(");
        int end = source.indexOf("public void onWorldChange(", start);
        String method = source.substring(start, end);

        assertTrue(method.contains("contexts.clear(event.getPlayer().getUniqueId())"));
        assertTrue(method.contains("beginObserved("));
        assertTrue(method.contains("MovementContextType.TELEPORT"));
        assertFalse(method.contains("beginTrusted("));
    }

}
