package ac.grim.grimac.manager;

/**
 * Resolves the three staff-alert presentation aliases in one deterministic
 * order. Verbose/proxy are wrappers around the canonical [alert] template.
 */
final class StaffAlertTemplate {
    private StaffAlertTemplate() {
    }

    static String expand(String original, String alert, String verbose, String proxy) {
        if (original == null) return "";
        return original
                .replace("[verbose]", verbose == null ? "" : verbose)
                .replace("[proxy]", proxy == null ? "" : proxy)
                .replace("[alert]", alert == null ? "" : alert);
    }
}
