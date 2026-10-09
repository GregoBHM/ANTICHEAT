package ac.grim.grimac.platform.bukkit.integration;

import ac.grim.grimac.manager.integrity.CombatProvider;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;

/** Reflection-only bridge to CombatPlus' documented public API; no hard dependency is introduced. */
public final class CombatPlusProvider implements CombatProvider {
    private static final String PLUGIN_NAME = "CombatPlus";
    private static final String PROVIDER_CLASS = "me.nik.combatplus.api.CombatPlusAPIProvider";

    private volatile ClassLoader cachedLoader;
    private volatile Method getApiMethod;
    private volatile Method tagMethod;
    private volatile Method timedTagMethod;
    private volatile Method untagMethod;

    @Override
    public @NotNull String id() {
        return "combatplus";
    }

    @Override
    public boolean isAvailable() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
        if (plugin == null || !plugin.isEnabled()) return false;
        try {
            Object api = api(plugin);
            return api != null;
        } catch (ReflectiveOperationException | LinkageError ignored) {
            clearCache();
            return false;
        }
    }

    @Override
    public void tagPlayer(@NotNull UUID uuid, long durationMillis) {
        invoke(uuid, true, durationMillis);
    }

    @Override
    public void untagPlayer(@NotNull UUID uuid) {
        invoke(uuid, false, 0L);
    }

    public void clearCache() {
        cachedLoader = null;
        getApiMethod = null;
        tagMethod = null;
        timedTagMethod = null;
        untagMethod = null;
    }

    private void invoke(UUID uuid, boolean tag, long durationMillis) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
        if (plugin == null || !plugin.isEnabled()) return;
        try {
            Object api = api(plugin);
            if (api == null) return;

            if (tag && timedTagMethod != null) {
                timedTagMethod.invoke(api, uuid, durationMillis);
            } else {
                Method method = tag ? tagMethod : untagMethod;
                method.invoke(api, uuid);
            }
        } catch (ReflectiveOperationException | LinkageError ex) {
            clearCache();
            if (ex instanceof InvocationTargetException invocation && invocation.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("CombatPlus API unavailable", ex);
        }
    }

    private Object api(Plugin plugin) throws ReflectiveOperationException {
        ClassLoader loader = plugin.getClass().getClassLoader();
        if (loader != cachedLoader || getApiMethod == null || tagMethod == null || untagMethod == null) {
            synchronized (this) {
                if (loader != cachedLoader || getApiMethod == null || tagMethod == null || untagMethod == null) {
                    Class<?> providerClass = Class.forName(PROVIDER_CLASS, false, loader);
                    Method getter = providerClass.getMethod("getAPI");
                    Object api = getter.invoke(null);
                    if (api == null) return null;
                    Method tag = api.getClass().getMethod("tagPlayer", UUID.class);
                    Method timedTag = null;
                    try {
                        timedTag = api.getClass().getMethod(
                                "tagPlayer",
                                UUID.class,
                                long.class
                        );
                    } catch (NoSuchMethodException ignored) {
                        // Older CombatPlus API. UUID-only tagging remains
                        // supported, but exact duration cannot be mirrored.
                    }
                    Method untag = api.getClass().getMethod("unTagPlayer", UUID.class);
                    cachedLoader = loader;
                    getApiMethod = getter;
                    tagMethod = tag;
                    timedTagMethod = timedTag;
                    untagMethod = untag;
                    return api;
                }
            }
        }
        return getApiMethod.invoke(null);
    }
}
