package ac.grim.grimac.platform.bukkit.events;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.manager.integrity.MovementContextType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.util.Vector;

import java.util.Locale;

/**
 * Generic Bukkit compatibility bridge for server-authored movement.
 *
 * <p>This intentionally has no hard dependency on MagicSpells, RPGItems or CombatPlus. Most movement powers
 * eventually become a server velocity, teleport, flight grant or cancelled move event; recording those events
 * gives Grim a narrow trusted context without globally exempting the player. Plugins that need exact spell/power
 * attribution can still call GrimIntegrationAPI directly.</p>
 */
public final class MovementContextListener implements Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent event) {
        if (!enabled()) return;
        Player player = event.getPlayer();
        Vector velocity = event.getVelocity();
        double horizontal = Math.hypot(velocity.getX(), velocity.getZ());

        MovementContextType type;
        if (velocity.getY() > 0.45D) {
            type = MovementContextType.LAUNCH;
        } else if (horizontal > 0.85D && Math.abs(velocity.getY()) < 0.35D) {
            type = MovementContextType.DASH;
        } else {
            type = MovementContextType.KNOCKBACK;
        }

        long duration = config().getLongElse("movement-context-auto.velocity-duration-ms", 900L);
        String detail = String.format(Locale.ROOT, "v=%.3f,%.3f,%.3f", velocity.getX(), velocity.getY(), velocity.getZ());
        GrimAPI.INSTANCE.getMovementContextManager().beginObserved(player.getUniqueId(), type, "Bukkit:Velocity", duration, detail);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!enabled()) return;
        long duration = config().getLongElse("movement-context-auto.teleport-duration-ms", 1200L);
        String cause = event.getCause() == null ? "UNKNOWN" : event.getCause().name();
        GrimAPI.INSTANCE.getMovementContextManager().beginObserved(
                event.getPlayer().getUniqueId(), MovementContextType.TELEPORT, "Bukkit:Teleport", duration, "cause=" + cause);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCancelledMove(PlayerMoveEvent event) {
        if (!enabled() || !event.isCancelled()) return;
        long duration = config().getLongElse("movement-context-auto.cancelled-move-duration-ms", 300L);
        GrimAPI.INSTANCE.getMovementContextManager().beginObserved(
                event.getPlayer().getUniqueId(), MovementContextType.ROOT, "Bukkit:CancelledMove", duration);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFlightToggle(PlayerToggleFlightEvent event) {
        if (!enabled()) return;
        Player player = event.getPlayer();
        if (!event.isFlying() || !player.getAllowFlight()) return;
        long duration = config().getLongElse("movement-context-auto.flight-duration-ms", 3000L);
        GrimAPI.INSTANCE.getMovementContextManager().beginObserved(
                player.getUniqueId(), MovementContextType.FLIGHT, "Bukkit:AllowedFlight", duration);
    }

    private static boolean enabled() {
        ConfigManager config = config();
        return config != null && config.getBooleanElse("movement-context-auto.enabled", true);
    }

    private static ConfigManager config() {
        return GrimAPI.INSTANCE.getConfigManager().getConfig();
    }
}
