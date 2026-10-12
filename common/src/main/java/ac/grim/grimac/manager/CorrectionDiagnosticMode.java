package ac.grim.grimac.manager;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

enum CorrectionDiagnosticMode {
    OFF,
    VERBOSE,
    NORMAL;

    static CorrectionDiagnosticMode parse(
            @Nullable String raw,
            boolean legacyEnabled
    ) {
        if (raw == null || raw.isBlank()) {
            return legacyEnabled ? VERBOSE : OFF;
        }

        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "off" -> OFF;
            case "normal" -> NORMAL;
            case "verbose" -> VERBOSE;
            default -> VERBOSE;
        };
    }
}
