package ac.grim.grimac.manager.integrity;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MovementDetectionPolicyAuditTest {

    @Test
    void trustedVelocityIsContextualEvidenceNotSimulationBypass()
            throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/ac/grim/grimac/checks/impl/prediction/OffsetHandler.java"
        ));

        assertTrue(source.contains(
                "boolean anyVelocityContext ="
        ));
        assertTrue(source.contains(
                "trustedVelocityContext || observedVelocityContext"
        ));
        assertFalse(source.contains(
                "Trusted plugin-authored velocity is explicitly incompatible"
        ));
        assertTrue(source.contains(
                "environment.isSpecialMovementEnvironment(environmentContexts)"
        ));

        String eligibility = between(
                source,
                "final boolean enforcementEligible =",
                "EnvironmentContextManager environment ="
        );
        assertFalse(eligibility.contains("!trustedVelocityContext"));
    }


    @Test
    void discardedSimulationPredictionIsNeutralUntilEpisodeWindowExpires()
            throws IOException {
        String source = read(
                "src/main/java/ac/grim/grimac/checks/impl/prediction/OffsetHandler.java"
        );

        int discarded = source.indexOf(
                "if (!predictionComplete.isChecked())"
        );
        int checked = source.indexOf(
                "lastCheckedPredictionNanos = now",
                discarded
        );
        String block = source.substring(discarded, checked);

        assertTrue(block.contains("resetEnforcementState()"));
        assertTrue(block.contains("resetShortBlinkState()"));
        assertTrue(block.contains("lastCheckedPredictionNanos"));
        assertTrue(block.contains("specialEnvironmentFlagEpisodeWindowMillis"));
        assertFalse(block.contains("flagComponent("));
    }

    @Test
    void trustedVelocityCannotGloballySuppressPersistentSimulationCorrection()
            throws IOException {
        String source = read(
                "src/main/java/ac/grim/grimac/checks/impl/prediction/OffsetHandler.java"
        );

        String correction = between(
                source,
                "boolean episodeEvidenceAccepted =",
                "advantageGained = Math.min(advantageGained, maxCeiling);"
        );

        assertTrue(correction.contains("boolean severeMovement = false"));
        assertTrue(correction.contains("executeViolationSetback(this)"));
        assertFalse(correction.contains("&& !trustedVelocityContext"));
    }

    @Test
    void noSlowUsesPhysicalSessionAndSharedSpecialEpisode() throws IOException {
        String source = read(
                "src/main/java/ac/grim/grimac/checks/impl/movement/NoSlow.java"
        );

        assertTrue(source.contains("itemUseSessionTracker.activeSession()"));
        assertTrue(source.contains("resolveMovementEvidenceEpisode"));
        assertTrue(source.contains("hasTrustedVelocityContext"));
        assertTrue(source.contains("hasObservedVelocityContext"));

        String contextual = between(
                source,
                "private void handleContextual(",
                "private static @NotNull String componentFor("
        );
        assertFalse(contextual.contains("reward()"));
    }

    @Test
    void discardedNoSlowPredictionDoesNotEraseLiveContextEpisode()
            throws IOException {
        String source = read(
                "src/main/java/ac/grim/grimac/checks/impl/movement/NoSlow.java"
        );

        int discarded = source.indexOf(
                "if (!predictionComplete.isChecked())"
        );
        int checked = source.indexOf(
                "lastCheckedPredictionNanos = now",
                discarded
        );
        String block = source.substring(discarded, checked);

        assertTrue(block.contains("resetTickState()"));
        assertTrue(block.contains("lastCheckedPredictionNanos"));
        assertTrue(block.contains("episodeWindowMillis"));
        assertFalse(block.contains("flagComponent("));
    }

    @Test
    void antiKbScopesVelocityContextsToTheConcreteSample() throws IOException {
        String source = read(
                "src/main/java/ac/grim/grimac/checks/impl/velocity/KnockbackHandler.java"
        );

        assertTrue(source.contains(".assessVelocity("));
        assertTrue(source.contains("velocityContext.isUncertain()"));
        assertFalse(source.contains(
                "getMovementContextManager().hasTrustedVelocityContext(player.uuid)"
        ));
        assertFalse(source.contains(
                "getMovementContextManager().hasObservedVelocityContext(player.uuid)"
        ));
        assertTrue(source.contains("resolveMovementEvidenceEpisode"));

        String uncertain = between(
                source,
                "private void handleUncertainVelocityEvidence(",
                "private static @NotNull String velocityComponent("
        );
        assertFalse(uncertain.contains("reward()"));
        assertFalse(uncertain.contains("threshold *= multiplier"));
    }

    @Test
    void simulationMakesPluginVelocityContextualWithoutGivingOtherChecksABrowser() throws IOException {
        String simulation = read(
                "src/main/java/ac/grim/grimac/checks/impl/prediction/OffsetHandler.java"
        );
        String phase = read(
                "src/main/java/ac/grim/grimac/checks/impl/prediction/Phase.java"
        );
        String timer = read(
                "src/main/java/ac/grim/grimac/checks/impl/timer/Timer.java"
        );
        String reach = read(
                "src/main/java/ac/grim/grimac/checks/impl/combat/Reach.java"
        );

        assertTrue(simulation.contains("hasTrustedVelocityContext"));
        assertTrue(simulation.contains("boolean anyVelocityContext ="));
        assertTrue(simulation.contains("resolveMovementEvidenceEpisode"));
        assertTrue(simulation.contains("specialEnvironmentEpisodeFlagAccepted"));
        assertFalse(simulation.contains(
                "Trusted plugin-authored velocity is explicitly incompatible"
        ));
        assertFalse(phase.contains("hasTrustedVelocityContext"));
        assertFalse(timer.contains("hasTrustedVelocityContext"));
        assertFalse(reach.contains("hasTrustedVelocityContext"));
    }

    @Test
    void genericBukkitMovementBridgeNeverCreatesTrustedVelocity() throws IOException {
        String bridge = read(
                "../bukkit/src/main/java/ac/grim/grimac/platform/bukkit/events/MovementContextListener.java"
        );

        assertTrue(bridge.contains("beginObserved"));
        assertFalse(bridge.contains("beginTrusted"));
    }

    @Test
    void packetOrderChecksDoNotReadMovementTrustContexts() throws IOException {
        Path root = Path.of(
                "src/main/java/ac/grim/grimac/checks/impl/packetorder"
        );

        try (Stream<Path> files = Files.walk(root)) {
            for (Path path : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(path);
                assertFalse(
                        source.contains("hasTrustedVelocityContext"),
                        path.toString()
                );
            }
        }
    }


    @Test
    void simulationKeepsTheLastConfiguredRecoveryTickProtected() throws IOException {
        String source = read(
                "src/main/java/ac/grim/grimac/checks/impl/prediction/OffsetHandler.java"
        );

        assertTrue(source.contains("boolean environmentRecoveryActive = false"));
        assertTrue(source.contains("environmentRecoveryActive = true"));
        assertTrue(source.contains(
                "final boolean environmentProtectedWindow = specialEnvironment\n                || environmentRecoveryActive"
        ));
        assertTrue(source.contains(
                "simulationComponent(\n                    environmentContexts,\n                    anyVelocityContext,\n                    environmentRecoveryActive"
        ));
        assertTrue(source.contains(
                "|| (!specialEnvironment && !environmentRecoveryActive)"
        ));
    }

    @Test
    void simulationPersistentCorrectionIsIndependentFromExtraVlSlots()
            throws IOException {
        String source = read(
                "src/main/java/ac/grim/grimac/checks/impl/prediction/OffsetHandler.java"
        );

        int correctionIncrement = source.indexOf(
                "specialEnvironmentCorrectionStreak++"
        );
        int episodeBudget = source.indexOf(
                "player.detectionEpisodeManager.evaluate(",
                correctionIncrement
        );
        int flagBudgetGate = source.indexOf(
                "if (specialFlagAllowed)",
                episodeBudget
        );
        int persistentEvidence = source.indexOf(
                "boolean episodeEvidenceAccepted =",
                flagBudgetGate
        );
        int correction = source.indexOf(
                "executeViolationSetback(this)",
                persistentEvidence
        );

        assertTrue(correctionIncrement >= 0);
        assertTrue(episodeBudget > correctionIncrement);
        assertTrue(flagBudgetGate > episodeBudget);
        assertTrue(persistentEvidence > flagBudgetGate);
        assertTrue(correction > persistentEvidence);
        assertFalse(source.contains("if (!specialFlagAllowed)"));
        assertTrue(source.contains("specialEnvironmentEpisodeFlagAccepted"));
        assertTrue(source.contains("Episode budgets cap VL/alerts only"));
    }

    @Test
    void antiKbSetbackVelocityCannotSpendSpecialEpisodeBudget()
            throws IOException {
        String source = read(
                "src/main/java/ac/grim/grimac/checks/impl/velocity/KnockbackHandler.java"
        );

        String uncertain = between(
                source,
                "private void handleUncertainVelocityEvidence(",
                "private static @NotNull String velocityComponent("
        );

        int setback = uncertain.indexOf("if (data.isSetback)");
        int episode = uncertain.indexOf("detectionEpisodeManager.evaluate(");

        assertTrue(setback >= 0);
        assertTrue(episode > setback);
    }


    @Test
    void skippedAntiKbPredictionIsNeutralWhileExplicitEventCancellationBreaksSimulationEpisode()
            throws IOException {
        String antiKb = read(
                "src/main/java/ac/grim/grimac/checks/impl/velocity/KnockbackHandler.java"
        );
        String simulation = read(
                "src/main/java/ac/grim/grimac/checks/impl/prediction/OffsetHandler.java"
        );

        int kbSkip = antiKb.indexOf(
                "if (!predictionComplete.isChecked())"
        );
        int kbReturn = antiKb.indexOf("return;", kbSkip);
        String kbBlock = antiKb.substring(kbSkip, kbReturn);

        assertFalse(kbBlock.contains("forceExempt()"));
        assertFalse(kbBlock.contains("normalEnforcementStreak = 0"));
        assertTrue(kbBlock.contains("knockbackPointThree = false"));

        int eventCancel = simulation.indexOf(
                "if (COMPLETE_CHANNEL.fire(player, this, offset))"
        );
        int eventReturn = simulation.indexOf("return;", eventCancel);
        String eventBlock = simulation.substring(eventCancel, eventReturn);

        assertTrue(eventBlock.contains("specialEnvironmentFlagStreak = 0"));
        assertTrue(eventBlock.contains("specialEnvironmentCorrectionStreak = 0"));
        assertTrue(eventBlock.contains("specialEnvironmentEpisodeFlagAccepted = false"));
    }


    @Test
    void specialSimulationCorrectionIsOneShotPerPhysicalEpisode()
            throws IOException {
        String source = read(
                "src/main/java/ac/grim/grimac/checks/impl/prediction/OffsetHandler.java"
        );

        assertTrue(source.contains(
                "private boolean specialEnvironmentEpisodeCorrectionApplied"
        ));
        assertTrue(source.contains(
                "boolean correctionApplied = environmentProtectedWindow"
        ));
        assertTrue(source.contains(
                "&& !correctionApplied"
        ));
        assertTrue(source.contains(
                "specialEnvironmentEpisodeCorrectionApplied = true"
        ));
        assertTrue(source.contains(
                "specialEnvironmentEpisodeCorrectionApplied = false"
        ));
    }


    @Test
    void pluginVelocityNoSlowContextUsesTheSameBoundedPolicyAsObservedVelocity()
            throws IOException {
        String source = read(
                "src/main/java/ac/grim/grimac/checks/impl/movement/NoSlow.java"
        );

        int anyVelocity = source.indexOf(
                "boolean anyVelocityContext ="
        );
        int policy = source.indexOf("if (!contextPolicyEnabled)");
        int contextual = source.indexOf(
                "if (specialEnvironment || anyVelocityContext)"
        );

        assertTrue(anyVelocity >= 0);
        assertTrue(policy > anyVelocity);
        assertTrue(contextual > policy);
        assertFalse(source.contains("if (trustedVelocityContext) {"));
    }

    private static String read(String path) throws IOException {
        return Files.readString(Path.of(path));
    }

    private static String between(
            String source,
            String startMarker,
            String endMarker
    ) {
        int start = source.indexOf(startMarker);
        int end = source.indexOf(endMarker, start + startMarker.length());
        assertTrue(start >= 0 && end > start);
        return source.substring(start, end);
    }
}
