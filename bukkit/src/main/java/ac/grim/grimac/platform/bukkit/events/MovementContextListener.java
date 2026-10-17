package ac.grim.grimac.platform.bukkit.events;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.api.config.ConfigManager;
import ac.grim.grimac.manager.integrity.MovementContextType;
import ac.grim.grimac.utils.math.Vector3dm;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
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
 * gives Grim a narrow observed context without globally exempting the player. Observed contexts may reduce
 * confidence in ambiguous movement but never create a trusted velocity bypass. Plugins that need exact
 * spell/power attribution can still call GrimIntegrationAPI directly.</p>
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
        GrimAPI.INSTANCE.getMovementContextManager().beginObservedVelocity(
                player.getUniqueId(),
                type,
                "Bukkit:Velocity",
                duration,
                new Vector3dm(
                        velocity.getX(),
                        velocity.getY(),
                        velocity.getZ()
                ),
                detail
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        var contexts = GrimAPI.INSTANCE.getMovementContextManager();
        // Trusted/observed movement authored before a teleport belongs to the
        // old position. Cleanup is lifecycle-owned and must run even when the
        // automatic Bukkit observation bridge is disabled.
        contexts.clear(event.getPlayer().getUniqueId());
        GrimAPI.INSTANCE.getSanctionEpisodeManager().markContinuityBreak(
                event.getPlayer().getUniqueId(),
                "teleport:" + (event.getCause() == null ? "UNKNOWN" : event.getCause().name())
        );
        if (!enabled()) return;

        long duration = config().getLongElse("movement-context-auto.teleport-duration-ms", 1200L);
        String cause = event.getCause() == null ? "UNKNOWN" : event.getCause().name();
        contexts.beginObserved(
                event.getPlayer().getUniqueId(), MovementContextType.TELEPORT, "Bukkit:Teleport", duration, "cause=" + cause);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        var player = event.getPlayer();
        var contexts = GrimAPI.INSTANCE.getMovementContextManager();
        contexts.clear(player.getUniqueId());
        GrimAPI.INSTANCE.getSanctionEpisodeManager().markContinuityBreak(
                player.getUniqueId(),
                "world-change"
        );
        if (!enabled()) return;

        long duration = config().getLongElse("movement-context-auto.teleport-duration-ms", 1200L);
        contexts.beginObserved(
                player.getUniqueId(),
                MovementContextType.TELEPORT,
                "Bukkit:WorldChange",
                duration,
                "from=" + event.getFrom().getName() + ",to=" + player.getWorld().getName()
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        GrimAPI.INSTANCE.getMovementContextManager()
                .clear(event.getEntity().getUniqueId());
        GrimAPI.INSTANCE.getSanctionEpisodeManager().markContinuityBreak(
                event.getEntity().getUniqueId(),
                "death"
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        // Respawn is a hard movement lifecycle boundary. Trusted and observed
        // velocity authored before death must never survive into the new life.
        GrimAPI.INSTANCE.getMovementContextManager()
                .clear(event.getPlayer().getUniqueId());
        GrimAPI.INSTANCE.getSanctionEpisodeManager().markContinuityBreak(
                event.getPlayer().getUniqueId(),
                "respawn"
        );
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        // A gamemode switch is a movement-policy discontinuity. This cleanup is
        // independent from movement-context-auto because explicit trusted
        // plugin contexts must not survive into the new mode either.
        GrimAPI.INSTANCE.getMovementContextManager()
                .clear(event.getPlayer().getUniqueId());
        GrimAPI.INSTANCE.getSanctionEpisodeManager().markContinuityBreak(
                event.getPlayer().getUniqueId(),
                "gamemode-change"
        );
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
