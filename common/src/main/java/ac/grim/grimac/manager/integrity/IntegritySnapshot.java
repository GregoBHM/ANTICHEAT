package ac.grim.grimac.manager.integrity;

import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;

/** Immutable diagnostics snapshot for integrations and staff tooling. */
public final class IntegritySnapshot {
    private final boolean combatTagged;
    private final boolean combatHeld;
    private final long combatRemainingMillis;
    private final double pendingFallDistance;
    private final double correlationScore;
    private final List<MovementContextManager.Context> contexts;
    private final List<InteractionContextManager.Context> interactionContexts;

    public IntegritySnapshot(boolean combatTagged,
                             boolean combatHeld,
                             long combatRemainingMillis,
                             double pendingFallDistance,
                             double correlationScore,
                             @NotNull List<MovementContextManager.Context> contexts,
                             @NotNull List<InteractionContextManager.Context> interactionContexts) {
        this.combatTagged = combatTagged;
        this.combatHeld = combatHeld;
        this.combatRemainingMillis = Math.max(0L, combatRemainingMillis);
        this.pendingFallDistance = Math.max(0.0D, pendingFallDistance);
        this.correlationScore = Math.max(0.0D, correlationScore);
        this.contexts = Collections.unmodifiableList(contexts);
        this.interactionContexts = Collections.unmodifiableList(interactionContexts);
    }

    public boolean isCombatTagged() {
        return combatTagged;
    }

    public boolean isCombatHeld() {
        return combatHeld;
    }

    public long getCombatRemainingMillis() {
        return combatRemainingMillis;
    }

    public double getPendingFallDistance() {
        return pendingFallDistance;
    }

    public double getCorrelationScore() {
        return correlationScore;
    }

    public @NotNull List<MovementContextManager.Context> getContexts() {
        return contexts;
    }

    public @NotNull List<InteractionContextManager.Context> getInteractionContexts() {
        return interactionContexts;
    }

    public String contextSummary() {
        if (contexts.isEmpty() && interactionContexts.isEmpty()) return "none";
        StringBuilder out = new StringBuilder();
        for (MovementContextManager.Context context : contexts) {
            if (out.length() > 0) out.append(',');
            out.append(context.getType().name()).append('@').append(context.getSource())
                    .append(':').append(context.getRemainingMillis()).append("ms");
            if (context.getDetail() != null) out.append('(').append(context.getDetail()).append(')');
        }
        for (InteractionContextManager.Context context : interactionContexts) {
            if (out.length() > 0) out.append(',');
            out.append("I:").append(context.getType().name()).append('@').append(context.getSource())
                    .append(':').append(context.getRemainingMillis()).append("ms");
            if (context.getDetail() != null) out.append('(').append(context.getDetail()).append(')');
        }
        return out.toString();
    }
}
