package ac.grim.grimac.manager;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Set;

/**
 * Backward-compatible punishment selector precedence.
 *
 * <p>Canonical names always win over aliases. An alias that is also the
 * canonical name of another check is shadowed so a selector such as NoSlow
 * cannot accidentally select FastConsume. Stable selectors are explicit and
 * exact.</p>
 */
final class PunishmentSelectorPolicy {
    private static final int STABLE_EXACT = 500;
    private static final int NAME_EXACT = 400;
    private static final int NAME_CONTAINS = 300;
    private static final int ALIAS_EXACT = 200;
    private static final int ALIAS_CONTAINS = 100;

    private PunishmentSelectorPolicy() {
    }

    static int matchPriority(
            @Nullable String rawSelector,
            @Nullable String checkName,
            @Nullable String alternativeName,
            @Nullable String stableKey,
            @NotNull Set<String> canonicalNames
    ) {
        String selector = normalize(rawSelector);
        if (selector.isEmpty()) return 0;

        boolean stableSelector = selector.startsWith("stable:")
                || selector.startsWith("key:");

        if (stableSelector) {
            String requested =
                    selector.substring(selector.indexOf(':') + 1).trim();
            return !requested.isEmpty()
                    && requested.equals(normalize(stableKey))
                    ? STABLE_EXACT
                    : 0;
        }

        String canonical = normalize(checkName);
        if (canonical.equals(selector)) {
            return NAME_EXACT;
        }
        if (!canonical.isEmpty() && canonical.contains(selector)) {
            return NAME_CONTAINS;
        }

        String alias = usableAlias(alternativeName, canonicalNames);
        if (alias.equals(selector)) {
            return ALIAS_EXACT;
        }
        if (!alias.isEmpty() && alias.contains(selector)) {
            return ALIAS_CONTAINS;
        }

        return 0;
    }

    static int exactMatchPriority(
            @Nullable String rawSelector,
            @Nullable String checkName,
            @Nullable String alternativeName,
            @Nullable String stableKey,
            @NotNull Set<String> canonicalNames
    ) {
        String selector = normalize(rawSelector);
        if (selector.isEmpty()) return 0;

        boolean stableSelector = selector.startsWith("stable:")
                || selector.startsWith("key:");

        if (stableSelector) {
            String requested =
                    selector.substring(selector.indexOf(':') + 1).trim();
            return !requested.isEmpty()
                    && requested.equals(normalize(stableKey))
                    ? STABLE_EXACT
                    : 0;
        }

        if (normalize(checkName).equals(selector)) {
            return NAME_EXACT;
        }

        return usableAlias(alternativeName, canonicalNames).equals(selector)
                ? ALIAS_EXACT
                : 0;
    }

    private static @NotNull String usableAlias(
            @Nullable String alternativeName,
            @NotNull Set<String> canonicalNames
    ) {
        String alias = normalize(alternativeName);
        if (alias.isEmpty()
                || alias.equals("unknown")
                || alias.equals("default")
                || canonicalNames.contains(alias)) {
            return "";
        }
        return alias;
    }

    private static @NotNull String normalize(@Nullable String value) {
        return value == null
                ? ""
                : value.trim().toLowerCase(Locale.ROOT);
    }
}
