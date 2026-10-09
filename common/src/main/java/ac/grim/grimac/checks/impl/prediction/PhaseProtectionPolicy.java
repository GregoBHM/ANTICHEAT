package ac.grim.grimac.checks.impl.prediction;

final class PhaseProtectionPolicy {
    private PhaseProtectionPolicy() {
    }

    static boolean shouldImmediateSetback(
            boolean protectionEnabled,
            boolean immediateSetbackEnabled,
            boolean explicitlyProtectedMaterial
    ) {
        return protectionEnabled
                && immediateSetbackEnabled
                && explicitlyProtectedMaterial;
    }
}
