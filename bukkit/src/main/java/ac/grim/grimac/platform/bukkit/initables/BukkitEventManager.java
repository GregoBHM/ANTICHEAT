package ac.grim.grimac.platform.bukkit.initables;

import ac.grim.grimac.manager.init.start.StartableInitable;
import ac.grim.grimac.platform.bukkit.GrimACBukkitLoaderPlugin;
import ac.grim.grimac.platform.bukkit.events.PistonEvent;
import ac.grim.grimac.platform.bukkit.events.CancelledBlockIntegrityListener;
import ac.grim.grimac.platform.bukkit.events.CombatIntegrityListener;
import ac.grim.grimac.platform.bukkit.events.MovementContextListener;
import ac.grim.grimac.platform.bukkit.events.InteractionIntegrityListener;
import ac.grim.grimac.utils.anticheat.LogUtil;
import org.bukkit.Bukkit;

public class BukkitEventManager implements StartableInitable {
    public void start() {
        LogUtil.info("Registering bukkit events... (PistonEvent, CombatIntegrityListener, CancelledBlockIntegrityListener, MovementContextListener, InteractionIntegrityListener)");

        Bukkit.getPluginManager().registerEvents(new PistonEvent(), GrimACBukkitLoaderPlugin.LOADER);
        Bukkit.getPluginManager().registerEvents(new CombatIntegrityListener(), GrimACBukkitLoaderPlugin.LOADER);
        Bukkit.getPluginManager().registerEvents(new CancelledBlockIntegrityListener(), GrimACBukkitLoaderPlugin.LOADER);
        Bukkit.getPluginManager().registerEvents(new MovementContextListener(), GrimACBukkitLoaderPlugin.LOADER);
        Bukkit.getPluginManager().registerEvents(new InteractionIntegrityListener(), GrimACBukkitLoaderPlugin.LOADER);
    }
}
