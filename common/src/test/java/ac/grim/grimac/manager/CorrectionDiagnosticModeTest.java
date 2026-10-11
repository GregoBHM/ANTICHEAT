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
                CorrectionDiagnosticMode.VERBOSE,
                CorrectionDiagnosticMode.parse("invalid", true)
        );
    }
}
