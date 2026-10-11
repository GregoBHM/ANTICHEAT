package ac.grim.grimac.platform.bukkit.events;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.checks.impl.integrity.AttackFrequency;
import ac.grim.grimac.checks.impl.integrity.ConsumeTiming;
import ac.grim.grimac.checks.impl.integrity.InventoryFrequency;
import ac.grim.grimac.checks.impl.integrity.PacketBurst;
import ac.grim.grimac.manager.integrity.InteractionContextType;
import ac.grim.grimac.player.GrimPlayer;
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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.UUID;

public final class InteractionIntegrityListener implements Listener {
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onConsumePrevent(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        GrimPlayer grim = grim(uuid);
        if (grim == null) return;

        ConsumeTiming consume = grim.checkManager.get(ConsumeTiming.class);
        if (consume == null) return;

        if (event.isCancelled()) {
            consume.clearPendingDecision();
            return;
        }

        String material = event.getItem().getType().name();
        ConsumeTiming.ConsumeDecision decision = consume.evaluateServerConsume(System.currentTimeMillis(), material);
        consume.setPendingDecision(decision);

        if (decision.shouldPrevent()) {
            event.setCancelled(true);
            try {
                player.updateInventory();
            } catch (RuntimeException ignored) {
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onConsumeMonitor(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        GrimPlayer grim = grim(uuid);
        if (grim == null) {
            return;
        }

        ConsumeTiming consume = grim.checkManager.get(ConsumeTiming.class);
        if (consume == null) {
            return;
        }

        ConsumeTiming.ConsumeDecision decision = consume.takePendingDecision();

        // An explicit integration context may be registered by a trusted plugin
        // after HIGHEST but before MONITOR. Honor that narrow late context
        // instead of applying a FastConsume decision created earlier in the
        // same Bukkit event. Generic cancellation alone is not enough to bypass
        // a Grim prevention decision.
        if (GrimAPI.INSTANCE.getInteractionContextManager()
                .suppressesConsumeTiming(uuid)) {
            grim.runSafely(consume::reset);
            return;
        }

        if (event.isCancelled() && (decision == null || !decision.shouldPrevent())) {
            GrimAPI.INSTANCE.getInteractionContextManager().begin(
                    uuid,
                    InteractionContextType.PLUGIN_CANCELLED_USE,
                    "Bukkit:CancelledConsume",
                    700L,
                    "item=" + event.getItem().getType().name()
            );
            grim.runSafely(consume::reset);
            return;
        }

        if (decision != null) {
            // Bukkit consume events run on the platform thread while Grim's
            // checks/punishment state are player-event-loop owned. Move the
            // actual flag/reward mutation back onto the player's event loop.
            grim.runSafely(() -> consume.applyServerConsumeDecision(decision));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCancelledInteract(PlayerInteractEvent event) {
        if (!event.isCancelled() || event.getItem() == null) return;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        UUID uuid = event.getPlayer().getUniqueId();
        GrimAPI.INSTANCE.getInteractionContextManager().begin(
                uuid,
                InteractionContextType.PLUGIN_CANCELLED_USE,
                "Bukkit:CancelledInteract",
                500L,
                "item=" + event.getItem().getType().name()
        );
        GrimPlayer grim = grim(uuid);
        if (grim != null) grim.runSafely(() -> grim.checkManager.get(ConsumeTiming.class).clearPendingDecision());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        GrimPlayer grim = grim(player.getUniqueId());
        if (grim == null) return;
        grim.runSafely(() -> {
            grim.checkManager.get(InventoryFrequency.class).reset();
            // Closing an inventory does not establish that the player stopped
            // using the held item. Preserve the physical session and discard
            // only a pending Bukkit consume decision.
            grim.checkManager.get(ConsumeTiming.class).clearPendingDecision();
        });
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
        // Teleport invalidates pending interaction evidence but does not prove
        // that the client stopped physically using the held item.
        clearLifecycle(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        clearLifecycle(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent event) {
        // Any gamemode transition invalidates the previous interaction state.
        // CombatIntegrityListener ends the physical use with the explicit
        // GAMEMODE_CHANGE reason.
        clearLifecycle(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        GrimAPI.INSTANCE.getInteractionContextManager().clear(uuid);
    }

    private static void clearLifecycle(UUID uuid) {
        GrimAPI.INSTANCE.getInteractionContextManager().clear(uuid);
        GrimPlayer grim = grim(uuid);
        if (grim == null) return;
        grim.runSafely(() -> {
            grim.checkManager.get(InventoryFrequency.class).reset();
            grim.checkManager.get(ConsumeTiming.class).clearPendingDecision();
            // Physical item-use lifecycle is owned by the explicit DEATH,
            // RESPAWN and GAMEMODE_CHANGE reset paths. Teleport/world change
            // intentionally preserve it. Do not erase the session here with
            // ConsumeTiming.reset(), which would collapse the explicit reason
            // into a generic CLEAR depending on Bukkit listener order.
            grim.checkManager.get(PacketBurst.class).reset();
            grim.checkManager.get(AttackFrequency.class).reset();
            grim.detectionEpisodeManager.clearAll();
        });
    }

    private static GrimPlayer grim(UUID uuid) {
        return GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(uuid);
    }
}
