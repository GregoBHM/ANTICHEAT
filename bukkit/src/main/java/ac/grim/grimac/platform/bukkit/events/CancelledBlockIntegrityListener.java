package ac.grim.grimac.platform.bukkit.events;

import ac.grim.grimac.GrimAPI;
import ac.grim.grimac.player.GrimPlayer;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import com.github.retrooper.packetevents.util.Vector3i;
import org.bukkit.event.block.BlockPlaceEvent;

/** Records placements cancelled by protection/build plugins so the client-only block cannot become movement support. */
public final class CancelledBlockIntegrityListener implements Listener {

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlockPlaced();
        String worldName = block.getWorld().getName();

        if (!event.isCancelled()) {
            GrimAPI.INSTANCE.getCancelledBlockIntegrityManager().confirmPlacement(
                    worldName,
                    block.getX(),
                    block.getY(),
                    block.getZ()
            );
            return;
        }

        GrimAPI.INSTANCE.getCancelledBlockIntegrityManager().recordCancelledPlacement(
                player.getUniqueId(),
                worldName,
                block.getX(),
                block.getY(),
                block.getZ()
        );

        GrimPlayer grimPlayer = GrimAPI.INSTANCE.getPlayerDataManager()
                .getPlayer(player.getUniqueId());

        if (grimPlayer != null) {
            grimPlayer.resyncPosition(new Vector3i(
                    block.getX(),
                    block.getY(),
                    block.getZ()
            ));
        }
    }
}
