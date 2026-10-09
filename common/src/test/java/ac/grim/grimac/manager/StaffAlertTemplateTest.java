package ac.grim.grimac.manager;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class StaffAlertTemplateTest {

    @Test
    void verboseWrapsCanonicalAlert() {
        String rendered = StaffAlertTemplate.expand(
                "[verbose]",
                "ALERT(%check_name%)",
                "[alert] DETAILS(%verbose%)",
                "[proxy] [alert]"
        );

        assertEquals("ALERT(%check_name%) DETAILS(%verbose%)", rendered);
        assertFalse(rendered.contains("[alert]"));
    }

    @Test
    void proxyWrapsCanonicalAlertWithoutLosingItsMarker() {
        String rendered = StaffAlertTemplate.expand(
                "[proxy]",
                "ALERT(%check_name%)",
                "[alert] DETAILS(%verbose%)",
                "&8[proxy] [alert]"
        );

        assertEquals("&8[proxy] ALERT(%check_name%)", rendered);
        assertFalse(rendered.endsWith("[alert]"));
    }
}
