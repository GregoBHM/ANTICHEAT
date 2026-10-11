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
import java.util.concurrent.ConcurrentHashMap;

public final class InteractionIntegrityListener implements Listener {
    private static final ConcurrentHashMap<UUID, ConsumeTiming.ConsumeDecision> PENDING_CONSUME = new ConcurrentHashMap<>();

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onConsumePrevent(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        GrimPlayer grim = grim(uuid);
        if (grim == null) return;

        ConsumeTiming consume = grim.checkManager.get(ConsumeTiming.class);
        if (consume == null) return;

        if (event.isCancelled()) {
            PENDING_CONSUME.remove(uuid);
            return;
        }

        String material = event.getItem().getType().name();
        ConsumeTiming.ConsumeDecision decision = consume.evaluateServerConsume(System.currentTimeMillis(), material);
        PENDING_CONSUME.put(uuid, decision);

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
            PENDING_CONSUME.remove(uuid);
            return;
        }

        ConsumeTiming consume = grim.checkManager.get(ConsumeTiming.class);
        if (consume == null) {
            PENDING_CONSUME.remove(uuid);
            return;
        }

        ConsumeTiming.ConsumeDecision decision = PENDING_CONSUME.remove(uuid);

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
        clear(event.getEntity().getUniqueId(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        clear(event.getPlayer().getUniqueId(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        // Teleport invalidates pending interaction evidence but does not prove
        // that the client stopped physically using the held item.
        clear(event.getPlayer().getUniqueId(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        clear(event.getPlayer().getUniqueId(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameMode(PlayerGameModeChangeEvent event) {
        // Any gamemode transition invalidates the previous interaction state.
        // CombatIntegrityListener ends the physical use with the explicit
        // GAMEMODE_CHANGE reason.
        clear(event.getPlayer().getUniqueId(), true);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        PENDING_CONSUME.remove(uuid);
        GrimAPI.INSTANCE.getInteractionContextManager().clear(uuid);
    }

    private static void clear(UUID uuid, boolean resetConsume) {
        PENDING_CONSUME.remove(uuid);
        GrimAPI.INSTANCE.getInteractionContextManager().clear(uuid);
        GrimPlayer grim = grim(uuid);
        if (grim == null) return;
        grim.runSafely(() -> {
            grim.checkManager.get(InventoryFrequency.class).reset();
            if (resetConsume) {
                grim.checkManager.get(ConsumeTiming.class).reset();
            }
            grim.checkManager.get(PacketBurst.class).reset();
            grim.checkManager.get(AttackFrequency.class).reset();
            grim.detectionEpisodeManager.clearAll();
        });
    }

    private static GrimPlayer grim(UUID uuid) {
        return GrimAPI.INSTANCE.getPlayerDataManager().getPlayer(uuid);
    }
}
