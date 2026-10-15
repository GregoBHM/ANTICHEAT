package ac.grim.grimac.manager.integrity;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import org.jetbrains.annotations.NotNull;

/**
 * Native protocol-47 clients have weak inventory/movement ordering evidence.
 * Keep detection active, but never destructively cancel CLICK_WINDOW from these
 * heuristic/order checks. Invalid crash/protocol packets remain handled elsewhere.
 */
public final class InventoryClickPreventionPolicy {
    private InventoryClickPreventionPolicy() {
    }

    public static boolean allowDestructiveHeuristicPrevention(
            @NotNull ClientVersion clientVersion
    ) {
        return clientVersion != ClientVersion.V_1_8;
    }
}
