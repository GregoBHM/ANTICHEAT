package ac.grim.grimac.platform.bukkit.events;

import ac.grim.grimac.GrimAPI;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;

/** Records placements cancelled by protection/build plugins so the client-only block cannot become movement support. */
public final class CancelledBlockIntegrityListener implements Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!event.isCancelled()) return;
        Player player = event.getPlayer();
        Block block = event.getBlockPlaced();
        GrimAPI.INSTANCE.getCancelledBlockIntegrityManager().recordCancelledPlacement(
                player.getUniqueId(),
                block.getWorld().getName(),
                block.getX(),
                block.getY(),
                block.getZ()
        );
    }
}
