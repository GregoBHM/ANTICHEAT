package ac.grim.grimac.manager;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CorrectionDiagnosticModeTest {

    @Test
    void explicitModesAreParsedExactly() {
        assertEquals(
                CorrectionDiagnosticMode.OFF,
                CorrectionDiagnosticMode.parse("off", true)
        );
        assertEquals(
                CorrectionDiagnosticMode.VERBOSE,
                CorrectionDiagnosticMode.parse("verbose", true)
        );
        assertEquals(
                CorrectionDiagnosticMode.NORMAL,
                CorrectionDiagnosticMode.parse("normal", true)
        );
    }

    @Test
    void explicitModernModeWinsLegacyFallback() {
        assertEquals(
                CorrectionDiagnosticMode.NORMAL,
                CorrectionDiagnosticMode.parse("normal", false)
        );
        assertEquals(
                CorrectionDiagnosticMode.VERBOSE,
                CorrectionDiagnosticMode.parse("verbose", false)
        );
        assertEquals(
                CorrectionDiagnosticMode.OFF,
                CorrectionDiagnosticMode.parse("off", true)
        );
    }

    @Test
    void verboseIsSafeDefaultAndLegacyDisabledMapsToOff() {
        assertEquals(
                CorrectionDiagnosticMode.VERBOSE,
                CorrectionDiagnosticMode.parse(null, true)
        );
        assertEquals(
                CorrectionDiagnosticMode.OFF,
                CorrectionDiagnosticMode.parse(null, false)
        );
        assertEquals(
                CorrectionDiagnosticMode.OFF,
                CorrectionDiagnosticMode.parse("   ", false)
        );
        assertEquals(
                CorrectionDiagnosticMode.VERBOSE,
                CorrectionDiagnosticMode.parse("invalid", true)
        );
    }
}
