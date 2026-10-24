package dev.csl.foliaaccess;

import com.destroystokyo.paper.utils.PaperPluginLogger;
import io.papermc.paper.plugin.configuration.PluginMeta;
import io.papermc.paper.plugin.provider.classloader.ClassLoaderAccess;
import io.papermc.paper.plugin.provider.classloader.ConfiguredPluginClassLoader;
import io.papermc.paper.plugin.provider.classloader.PaperClassLoaderStorage;
import io.papermc.paper.plugin.provider.classloader.PluginClassLoaderGroup;
import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The classloader the plugin instance actually runs in.
 * <p>
 * It holds the plugin's own jar <em>and</em> its libraries as its own URLs, so
 * PacketEvents and friends are defined by this loader rather than by the
 * isolated one Paper builds. That is the whole point: Paper's library loader is
 * parented to Paper's own classloader
 * ({@code new URLClassLoader(urls, this.getClass().getClassLoader())} in
 * {@code PaperClasspathBuilder}), so it can never see another plugin, and
 * PacketEvents linking {@code com.viaversion.viaversion.api.Via} from there is a
 * guaranteed {@code NoClassDefFoundError}.
 *
 * <h2>Lookup order</h2>
 * Child-first for this loader's own URLs, then the parent. Child-first is
 * essential: the parent is the plugin's {@code PaperPluginClassLoader}, which
 * also holds the plugin jar, so parent-first would return its copy of the
 * plugin classes and leave the libraries in a different loader again — nothing
 * gained.
 * <p>
 * One exception, and it is derived from the parent rather than from a list of
 * packages: a class bundled from a <em>library</em> jar is offered to the
 * parent first, and the parent's copy wins when it does not come from this
 * loader's own jars. See {@link #loadPluginOrParent}. That keeps a single
 * identity for classes the server also defines — {@code JavaPlugin},
 * adventure's {@code Component} — without naming any package, so it adapts to
 * whatever a server actually provides (Purpur classes on Leaf, a library the
 * server bundles, a class it stops bundling).
 *
 * <h2>Plugin visibility</h2>
 * Before loading the main class, FoliaAccess registers this loader with
 * {@code PaperClassLoaderStorage.registerAccessBackedGroup}, reusing the
 * parent's access policy. That gives the library loader the same dependency
 * visibility as its plugin, while Paper's registration also prevents the later
 * unsafe-registration fallback and warning.
 *
 * <h2>Why it implements {@link ConfiguredPluginClassLoader}</h2>
 * {@code JavaPlugin}'s constructor requires it:
 * <pre>
 * if (cl instanceof ConfiguredPluginClassLoader ccl) ccl.init(this);
 * else throw new IllegalStateException("JavaPlugin requires to be created by a valid classloader.");
 * </pre>
 * and {@link #init} does the rest of what {@code PaperPluginClassLoader.init}
 * does for a normal plugin: build the {@code PluginDescriptionFile} from the
 * meta and hand the plugin its server, description, data folder, jar, this
 * loader and logger. Without that call the plugin instance is uninitialized —
 * null description, data folder and meta — and Paper's plugin manager fails on
 * the first {@code getPluginMeta()}.
 */
final class AccessClassLoader extends URLClassLoader implements ConfiguredPluginClassLoader {

    static {
        registerAsParallelCapable();
    }

    private final PluginMeta meta;
    private final Path source;
    private final Set<Path> ownJars;
    private final ClassLoader parent;
    /** The access-backed group returned by Paper when this loader is registered. */
    private volatile PluginClassLoaderGroup group;
    /** Set by {@link #init}, which {@code JavaPlugin}'s constructor calls. */
    private volatile JavaPlugin plugin;

    AccessClassLoader(URL[] urls, ClassLoader parent, PluginMeta meta, Path source) {
        super("foliaaccess-" + meta.getName(), urls, parent);
        this.parent = parent;
        this.meta = meta;
        this.source = source;
        Set<Path> jars = new HashSet<>();
        for (URL url : urls) {
            try {
                jars.add(Path.of(url.toURI()).toAbsolutePath().normalize());
            } catch (Exception ignored) {
                // not a file: URL — cannot be one of ours
            }
        }
        this.ownJars = jars;
    }

    /**
     * Registers this loader before the plugin main class is instantiated,
     * reusing the parent plugin's dependency-access policy.
     */
    void registerAccessBackedGroup() {
        if (!(parent instanceof ConfiguredPluginClassLoader configured)) {
            throw new IllegalStateException("FoliaAccess needs a configured parent plugin loader");
        }
        PluginClassLoaderGroup parentGroup = configured.getGroup();
        if (parentGroup == null) {
            throw new IllegalStateException("FoliaAccess parent plugin loader has no dependency group yet");
        }
        registerAccessBackedGroup(parentGroup.getAccess(), PaperClassLoaderStorage.instance());
    }

    /** Test seam; production uses the parent access policy and Paper singleton. */
    void registerAccessBackedGroup(ClassLoaderAccess access, PaperClassLoaderStorage storage) {
        synchronized (this) {
            if (group == null) {
                group = storage.registerAccessBackedGroup(this, access);
            }
        }
    }

    /** Undo an early registration if plugin construction fails. */
    void unregisterFromPaper() {
        if (group != null) unregisterFromPaper(PaperClassLoaderStorage.instance());
    }

    void unregisterFromPaper(PaperClassLoaderStorage storage) {
        synchronized (this) {
            if (group != null) {
                storage.unregisterClassloader(this);
                group = null;
            }
        }
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> found = findLoadedClass(name);
            // Ask the platform loader first instead of keeping a hand-maintained
            // prefix list for JDK packages. This includes bootstrap and
            // platform-module classes, while application/server/plugin classes
            // continue through the normal origin-based lookup below.
            if (found == null) {
                try {
                    found = ClassLoader.getPlatformClassLoader().loadClass(name);
                } catch (ClassNotFoundException ignored) {
                    found = loadPluginOrParent(name);
                }
            }
            if (found == null) throw new ClassNotFoundException(name);
            // Parent-defined classes are already linked by their defining loader.
            if (resolve && found.getClassLoader() == this) resolveClass(found);
            return found;
        }
    }

    /**
     * Child-first for classes in this loader, except when the parent already
     * has a copy from somewhere outside our own jars. The decision is made per
     * class from its defining artifact, never from a hard-coded package list.
     * <p>
     * This matters in both directions. If the server provides the bundled
     * class (e.g. {@code JavaPlugin} or an Adventure API class), the parent's
     * definition wins so there is one class identity server-wide. If Paper's
     * isolated library loader provides the same library class from one of our
     * jars, this loader's copy wins so the plugin and that library are in one
     * loader and the library can see other plugins through our parent.
     */
    private Class<?> loadPluginOrParent(String name) throws ClassNotFoundException {
        String resourceName = name.replace('.', '/') + ".class";
        URL ownResource = findResource(resourceName);
        if (ownResource == null) {
            // Let the parent resolve it first, then use this loader's
            // access-backed group if the parent cannot resolve it.
            return loadFromParentOrGroup(name);
        }
        if (isInPluginJar(ownResource)) {
            // Plugin classes must come from this loader, not the parent's copy.
            return findClass(name);
        }

        Class<?> parents = null;
        try {
            parents = parent.loadClass(name);
        } catch (ClassNotFoundException ignored) {
            // Try the access-backed group before defining our own library copy.
        }
        if (parents == null) parents = findInGroup(name);
        if (parents != null) {
            // A group lookup can resolve this class through this loader itself.
            if (parents.getClassLoader() == this) return parents;
            if (!fromOwnJars(parents, resourceName)) return parents;
        }
        return findClass(name);
    }

    private Class<?> loadFromParentOrGroup(String name) throws ClassNotFoundException {
        try {
            return parent.loadClass(name);
        } catch (ClassNotFoundException parentMiss) {
            Class<?> fromGroup = findInGroup(name);
            if (fromGroup != null) return fromGroup;
            throw parentMiss;
        }
    }

    private Class<?> findInGroup(String name) {
        PluginClassLoaderGroup currentGroup = group;
        return currentGroup == null ? null : currentGroup.getClassByName(name, false, this);
    }

    private boolean isInPluginJar(URL resource) {
        try {
            URL jar = ((JarURLConnection) resource.openConnection()).getJarFileURL();
            Path found = Path.of(jar.toURI()).toAbsolutePath().normalize();
            Path plugin = source.toAbsolutePath().normalize();
            return found.equals(plugin);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Whether the class the parent resolved came from any jar this loader
     * holds. Prefer exact code-source metadata; fall back to the defining
     * loader's URLs or the class resource when a runtime omits CodeSource.
     * If origin is still unknown, prefer the parent's class to preserve shared
     * type identity rather than guessing that it is a bundled copy.
     */
    private boolean fromOwnJars(Class<?> clazz, String resourceName) {
        try {
            CodeSource source = clazz.getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null) {
                return isOwnJar(source.getLocation());
            }
        } catch (SecurityException ignored) {
            // Use the loader/resource fallbacks below.
        }

        URL resource = clazz.getResource("/" + resourceName);
        if (resource != null) {
            try {
                if (resource.openConnection() instanceof JarURLConnection connection) {
                    return isOwnJar(connection.getJarFileURL());
                }
            } catch (IOException ignored) {
                // Fall back to the defining loader's exposed URLs.
            }
        }

        ClassLoader definingLoader = clazz.getClassLoader();
        if (definingLoader instanceof URLClassLoader urlClassLoader) {
            for (URL url : urlClassLoader.getURLs()) {
                if (isOwnJar(url)) return true;
            }
        }
        return false;
    }

    private boolean isOwnJar(URL url) {
        try {
            URL jar = url;
            if (url.openConnection() instanceof JarURLConnection connection) {
                jar = connection.getJarFileURL();
            }
            Path path = Path.of(jar.toURI()).toAbsolutePath().normalize();
            return ownJars.contains(path);
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------- ConfiguredPluginClassLoader

    /**
     * The four-argument lookup Paper uses when a plugin group asks this loader
     * for a class. It only exposes this loader's own URLs; the requesting
     * plugin's group is responsible for any further visibility.
     */
    @Override
    public Class<?> loadClass(String name, boolean resolve, boolean checkGlobal,
                              boolean checkLibraries) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> found = findLoadedClass(name);
            if (found == null) {
                found = findClass(name);
            }
            if (resolve) resolveClass(found);
            return found;
        }
    }

    @Override
    public PluginMeta getConfiguration() {
        return meta;
    }

    /**
     * What {@code PaperPluginClassLoader.init} does for a normal plugin:
     * rebuild the {@link PluginDescriptionFile} from the meta and hand the
     * plugin everything {@code JavaPlugin.init} stores. Called from the
     * {@code JavaPlugin} constructor, so the plugin is fully initialized by
     * the time {@code createPlugin} returns it to Paper.
     */
    @Override
    public void init(JavaPlugin plugin) {
        PluginMeta config = meta;
        PluginDescriptionFile description = new PluginDescriptionFile(
                config.getName(),
                config.getName(),
                config.getProvidedPlugins(),
                config.getMainClass(),
                "", // Classloader load order api
                config.getPluginDependencies(), // Dependencies
                config.getPluginSoftDependencies(), // Soft Depends
                config.getLoadBeforePlugins(), // Load Before
                config.getVersion(),
                Map.of(), // Commands, we use a separate system
                config.getDescription(),
                config.getAuthors(),
                config.getContributors(),
                config.getWebsite(),
                config.getLoggerPrefix(),
                config.getLoadOrder(),
                config.getPermissions(),
                config.getPermissionDefault(),
                Set.of(), // Aware api
                config.getAPIVersion(),
                List.of() // Libraries
        );
        File dataFolder = new File(Bukkit.getPluginsFolder(), description.getName());
        plugin.init(Bukkit.getServer(), description, dataFolder, source.toFile(), this, config,
                PaperPluginLogger.getLogger(config));
        this.plugin = plugin;
    }

    @Override
    public JavaPlugin getPlugin() {
        return plugin;
    }

    /** The registered group, or the parent plugin's group before registration. */
    @Override
    public PluginClassLoaderGroup getGroup() {
        PluginClassLoaderGroup registered = group;
        if (registered != null) return registered;
        return parent instanceof ConfiguredPluginClassLoader configured
                ? configured.getGroup()
                : null;
    }

    @Override
    public void close() throws IOException {
        super.close();
    }
}
