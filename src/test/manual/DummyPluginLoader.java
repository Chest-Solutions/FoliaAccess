package dev.csl.foliaaccess;

import org.bukkit.event.Event;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.PluginLoader;
import org.bukkit.plugin.RegisteredListener;

import java.io.File;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Stand-in for the server's PluginLoader service. JavaPlugin.init resolves it
 * through {@code ServiceLoader} (via adventure's Services helper), so the test
 * classpath must provide one — declared in
 * src/test/manual-resources/META-INF/services/org.bukkit.plugin.PluginLoader.
 */
public final class DummyPluginLoader implements PluginLoader {

    @Override
    public Plugin loadPlugin(File file) {
        return null;
    }

    @Override
    public PluginDescriptionFile getPluginDescription(File file) {
        return null;
    }

    @Override
    public Pattern[] getPluginFileFilters() {
        return new Pattern[0];
    }

    @Override
    public Map<Class<? extends Event>, Set<RegisteredListener>> createRegisteredListeners(Listener listener, Plugin plugin) {
        return Map.of();
    }

    @Override
    public void enablePlugin(Plugin plugin) {
    }

    @Override
    public void disablePlugin(Plugin plugin) {
    }
}
