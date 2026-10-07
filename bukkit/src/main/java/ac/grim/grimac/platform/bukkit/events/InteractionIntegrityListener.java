package ac.grim.grimac.platform.bukkit.events;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.checks.impl.integrity.AttackFrequency;
import ac.grim.grimac.checks.impl.integrity.ConsumeTiming;
import ac.grim.grimac.checks.impl.integrity.InventoryFrequency;
import ac.grim.grimac.checks.impl.integrity.PacketBurst;
import ac.grim.grimac.manager.integrity.InteractionContextType;
import ac.grim.grimac.player.GrimPlayer;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.UUID;

/** Bukkit confirmation/reset bridge for packet-side interaction integrity state. */
public final class InteractionIntegrityListener implements Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    public void onConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        GrimPlayer grim = grim(player.getUniqueId());
        if (grim == null) return;

        if (event.isCancelled()) {
            GrimAPI.INSTANCE.getInteractionContextManager().begin(player.getUniqueId(),
                    InteractionContextType.PLUGIN_CANCELLED_USE, "Bukkit:CancelledConsume", 700L,
                    "item=" + event.getItem().getType().name());
            grim.runSafely(() -> grim.checkManager.get(ConsumeTiming.class).reset());
            return;
        }

        String material = event.getItem().getType().name();
        long now = System.currentTimeMillis();
        grim.runSafely(() -> grim.checkManager.get(ConsumeTiming.class).completeServerConsume(now, material));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCancelledInteract(PlayerInteractEvent event) {
        if (!event.isCancelled() || event.getItem() == null) return;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        UUID uuid = event.getPlayer().getUniqueId();
        GrimAPI.INSTANCE.getInteractionContextManager().begin(uuid,
                InteractionContextType.PLUGIN_CANCELLED_USE, "Bukkit:CancelledInteract", 500L,
                "item=" + event.getItem().getType().name());
        GrimPlayer grim = grim(uuid);
        if (grim != null) grim.runSafely(() -> grim.checkManager.get(ConsumeTiming.class).reset());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        GrimPlayer grim = grim(player.getUniqueId());
        if (grim == null) return;
        grim.runSafely(() -> {
            grim.checkManager.get(InventoryFrequency.class).reset();
            grim.checkManager.get(ConsumeTiming.class).reset();
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        clear(event.getEntity().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        clear(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        clear(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        clear(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent event) {
        GameMode mode = event.getNewGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) clear(event.getPlayer().getUniqueId());
    }

    private static void clear(UUID uuid) {
        GrimAPI.INSTANCE.getInteractionContextManager().clear(uuid);
        GrimPlayer grim = grim(uuid);
        if (grim == null) return;
        grim.runSafely(() -> {
            grim.checkManager.get(InventoryFrequency.class).reset();
            grim.checkManager.get(ConsumeTiming.class).reset();
            grim.checkManager.get(PacketBurst.class).reset();
            grim.checkManager.get(AttackFrequency.class).reset();
        });
    }

    private static GrimPlayer grim(UUID uuid) {
        return GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(uuid);
    }
}
