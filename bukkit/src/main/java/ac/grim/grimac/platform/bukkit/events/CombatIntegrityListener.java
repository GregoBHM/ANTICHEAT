package ac.grim.grimac.platform.bukkit.events;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.manager.integrity.CombatIntegrityManager;
import ac.grim.grimac.platform.bukkit.integration.CombatPlusProvider;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.projectiles.ProjectileSource;

import java.util.UUID;

/**
 * Bukkit bridge for the platform-neutral combat integrity ledger.
 *
 * <p>Only non-cancelled, real Bukkit PvP damage starts/refreshed a tag. This avoids packet-only false tags in
 * WorldGuard/safe zones. ConnectionStall then protects that tag independently from whatever combat plugin the
 * server uses.</p>
 */
public final class CombatIntegrityListener implements Listener {
    private static final String EXEMPT_PERMISSION = "grim.exempt.connectionstall";
    private final CombatPlusProvider combatPlusProvider = new CombatPlusProvider();

    public CombatIntegrityListener() {
        GrimAPI.INSTANCE.getCombatIntegrityManager().setExternalProvider(combatPlusProvider);
    }

    private CombatIntegrityManager manager() {
        return GrimAPI.INSTANCE.getCombatIntegrityManager();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!manager().isEnabled()) return;
        if (!(event.getEntity() instanceof Player victim)) return;

        Player attacker = resolveAttacker(event.getDamager());
        if (attacker == null || attacker.getUniqueId().equals(victim.getUniqueId())) return;
        if (attacker.hasPermission(EXEMPT_PERMISSION) || victim.hasPermission(EXEMPT_PERMISSION)) return;
        if (event.getFinalDamage() <= 0.0D) return;

        manager().tag(attacker.getUniqueId(), victim.getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (player.hasPermission(EXEMPT_PERMISSION)) {
            manager().clearCombat(uuid);
            return;
        }

        if (!manager().shouldPunishIntegrityDisconnect(uuid)) return;

        if (manager().isKillOnUnsafeDisconnect() && !player.isDead() && player.getHealth() > 0.0D) {
            try {
                // setHealth(0) preserves Bukkit's normal death/drop pipeline better than inventing synthetic
                // fall damage and cannot be reduced by armor/resistance.
                player.setHealth(0.0D);
            } catch (RuntimeException ignored) {
                // Generic disconnect handling will leave a pending penalty as a fallback.
                return;
            }
        }

        manager().resolveUnsafeDisconnect(uuid);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        UUID uuid = event.getEntity().getUniqueId();
        manager().clearCombat(uuid);
        GrimAPI.INSTANCE.getFallIntegrityManager().clear(uuid);
        GrimAPI.INSTANCE.getMovementContextManager().clear(uuid);
        GrimAPI.INSTANCE.getIntegrityCorrelationManager().clear(uuid);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFallDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) return;
        // Once Bukkit reaches the real fall-damage pipeline, let the server/other plugins own the result.
        GrimAPI.INSTANCE.getFallIntegrityManager().clear(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        // A server-authorized teleport is a legitimate fall reset. CombatIntegrity remains independent.
        GrimAPI.INSTANCE.getFallIntegrityManager().clear(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        GrimAPI.INSTANCE.getFallIntegrityManager().clear(uuid);
        GrimAPI.INSTANCE.getMovementContextManager().clear(uuid);
        GrimAPI.INSTANCE.getIntegrityCorrelationManager().clear(uuid);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        GameMode mode = event.getNewGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) {
            GrimAPI.INSTANCE.getFallIntegrityManager().clear(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission(EXEMPT_PERMISSION)) return;
        if (!manager().shouldBlockCommand(player.getUniqueId(), event.getMessage())) return;

        event.setCancelled(true);
        if (!manager().isSilentProtection()) {
            String message = manager().getBlockMessage();
            if (message != null && !message.isEmpty()) {
                player.sendMessage(ChatColor.translateAlternateColorCodes('&', message));
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission(EXEMPT_PERMISSION)) {
            manager().clearCombat(player.getUniqueId());
            return;
        }

        if (!manager().consumePendingJoinPenalty(player.getUniqueId())) return;
        if (player.isDead() || player.getHealth() <= 0.0D) return;

        try {
            // PlayerJoinEvent already runs in the player's valid Bukkit/Folia context. Resolving here avoids
            // scheduling through Bukkit's global scheduler, which would be unsafe on Folia.
            player.setHealth(0.0D);
        } catch (RuntimeException ignored) {
            // Do not loop a broken penalty forever; consumePendingJoinPenalty is intentionally one-shot.
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginEnable(PluginEnableEvent event) {
        if (!"CombatPlus".equalsIgnoreCase(event.getPlugin().getName())) return;
        combatPlusProvider.clearCache();
        manager().setExternalProvider(combatPlusProvider);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginDisable(PluginDisableEvent event) {
        if (!"CombatPlus".equalsIgnoreCase(event.getPlugin().getName())) return;
        combatPlusProvider.clearCache();
        manager().setExternalProvider(null);
    }

    private static Player resolveAttacker(Entity damager) {
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Player player) return player;
        }
        return null;
    }
}
