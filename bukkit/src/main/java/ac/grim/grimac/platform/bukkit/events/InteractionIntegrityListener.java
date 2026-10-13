package ac.grim.grimac.platform.bukkit.events;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.checks.impl.integrity.AttackFrequency;
import ac.grim.grimac.checks.impl.integrity.InventoryFrequency;
import ac.grim.grimac.checks.impl.integrity.PacketBurst;
import ac.grim.grimac.player.GrimPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.UUID;

/** Interaction-integrity bridge that intentionally does not observe consumption. */
public final class InteractionIntegrityListener implements Listener {
    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        GrimPlayer grim = grim(player.getUniqueId());
        if (grim == null) return;
        grim.runSafely(() -> grim.checkManager.get(InventoryFrequency.class).reset());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        clearLifecycle(event.getEntity().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        clearLifecycle(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        clearLifecycle(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        clearLifecycle(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent event) {
        clearLifecycle(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        GrimAPI.INSTANCE.getInteractionContextManager()
                .clear(event.getPlayer().getUniqueId());
    }

    private static void clearLifecycle(UUID uuid) {
        GrimAPI.INSTANCE.getInteractionContextManager().clear(uuid);
        GrimPlayer grim = grim(uuid);
        if (grim == null) return;
        grim.runSafely(() -> {
            grim.checkManager.get(InventoryFrequency.class).reset();
            grim.checkManager.get(PacketBurst.class).reset();
            grim.checkManager.get(AttackFrequency.class).reset();
            grim.detectionEpisodeManager.clearAll();
        });
    }

    private static GrimPlayer grim(UUID uuid) {
        return GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(uuid);
    }
}
