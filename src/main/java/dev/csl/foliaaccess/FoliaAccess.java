package dev.csl.foliaaccess;

import io.papermc.paper.plugin.bootstrap.PluginProviderContext;
import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.library.impl.JarLibrary;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.graph.Exclusion;
import org.eclipse.aether.repository.RemoteRepository;
import io.papermc.paper.plugin.provider.classloader.ConfiguredPluginClassLoader;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarFile;

/**
 * Runs a Paper plugin in a classloader that holds its libraries alongside it,
 * rather than the isolated one Paper's library mechanism builds.
 *
 * <h2>Why</h2>
 * Paper puts libraries in a loader parented to Paper's own classloader
 * ({@code PaperClasspathBuilder}: {@code new URLClassLoader(urls,
 * this.getClass().getClassLoader())}). That loader is a sibling of the plugin's
 * classloader, not a child, and never consults the plugin classloader group —
 * so it cannot see another plugin. A library that resolves another plugin by
 * class reference fails: the probe runs on the plugin's classloader and
 * succeeds, the actual link runs on the library's and does not.
 * <pre>
 * ViaVersionUtil.load()        getPlugin().getClass().getClassLoader() -> finds Via
 * ViaVersionAccessorImpl:57    Via.getManager()  -> NoClassDefFoundError
 * </pre>
 * Shading fixes that by putting the library in the plugin jar. This does the
 * same at runtime, without the bloat.
 *
 * <h2>The classloader problem, and the actual fix</h2>
 * Paper runs a {@code PluginLoader} and a {@code PluginBootstrap} in different
 * classloaders. {@code PaperPluginProviderFactory} loads the {@code loader:}
 * class with a standalone {@code PaperSimplePluginClassLoader} and closes it
 * immediately; {@code PaperPluginParent} loads the bootstrapper with the
 * {@code PaperPluginClassLoader}. Both are parented to Paper's own classloader,
 * so they are siblings, and a class bundled in the plugin jar — this one — is
 * loaded <em>twice</em>. Static state cannot carry between the two phases.
 * That is why a loader-to-bootstrap handoff through a static map fails, and
 * why this class does not attempt one.
 * <p>
 * Instead the declaration travels through Paper's own machinery, which is the
 * one channel both phases genuinely share:
 * <ol>
 *   <li>The {@code PluginLoader} resolves the libraries and adds each jar to
 *       Paper's {@link PluginClasspathBuilder} — its intended job. No FoliaAccess
 *       state is stored.</li>
 *   <li>Paper builds the plugin's classloader with those jars in its library
 *       loader.</li>
 *   <li>{@code createPlugin} discovers the libraries in the classloader Paper
 *       built, using the standard manifest resource and subtracting resources
 *       already visible from its parent — then builds the combined loader.</li>
 * </ol>
 * Nothing crosses the classloader boundary except data Paper itself carried.
 * <p>
 * The read-back derives the jar list from the classloader's resources; no
 * library coordinates, package names, or server jar names are baked into it.
 * The combined loader decides per class, from the class's defining jar, whether
 * the parent's copy of a bundled class should win — see {@link AccessClassLoader}.
 *
 * <h2>Use</h2>
 * Declare in a {@code PluginLoader}, start in a {@code PluginBootstrap}:
 * <pre>{@code
 * public final class MyLoader implements PluginLoader {
 *     @Override public void classloader(PluginClasspathBuilder classpath) {
 *         FoliaAccess.configure(classpath)
 *                 .repository("https://repo.codemc.io/repository/maven-releases/")
 *                 .library("com.github.retrooper:packetevents-spigot:2.14.0")
 *                 .register();
 *     }
 * }
 *
 * public final class MyBootstrap implements PluginBootstrap {
 *     @Override public void bootstrap(BootstrapContext context) { }
 *     @Override public JavaPlugin createPlugin(PluginProviderContext context) {
 *         return FoliaAccess.launch(context);
 *     }
 * }
 * }</pre>
 * ```kotlin
 * paper {
 *     main = "com.example.MyPlugin"
 *     loader = "com.example.MyLoader"
 *     bootstrapper = "com.example.MyBootstrap"
 * }
 * ```
 * If there is no {@code PluginLoader}, declare in the bootstrapper and resolve
 * in one step instead:
 * <pre>{@code
 * return FoliaAccess.from(context).library("group:artifact:1.0").launch();
 * }</pre>
 * The bootstrapper-only path resolves directly and does not touch Paper's
 * builder; both paths converge on the same combined classloader.
 *
 * <h2>Dependencies</h2>
 * Transitive, preferring Gradle module metadata over the POM — see
 * {@link Resolver}. Declare the same artifacts {@code compileOnly} in Gradle
 * and remove any {@code library(...)} declarations that were feeding Paper's
 * own resolver.
 */
public final class FoliaAccess {

    private FoliaAccess() {
    }

    /**
     * Begins configuration from a {@code PluginLoader}. The builder feeds
     * Paper's {@link PluginClasspathBuilder}; nothing is stored in FoliaAccess,
     * because the loader and the bootstrapper run in different classloaders.
     */
    public static Builder configure(PluginClasspathBuilder classpath) {
        return new Builder(classpath);
    }

    /**
     * Begins configuration from a {@code PluginBootstrap}, for plugins with no
     * {@code PluginLoader}. Resolution happens here, in one classloader.
     */
    public static Builder from(PluginProviderContext context) {
        return new Builder(context);
    }

    /**
     * Starts the plugin from the libraries a {@code PluginLoader} registered
     * with Paper. Call from {@code PluginBootstrap.createPlugin}.
     * <p>
     * The library list is derived from the resources Paper exposes on its
     * plugin classloader, minus the plugin jar itself and jars already visible
     * from that loader's parent. No FoliaAccess state or hard-coded library list
     * is involved.
     *
     * @throws IllegalStateException if the registered jars cannot be discovered
     */
    public static JavaPlugin launch(PluginProviderContext context) {
        List<URL> libraries;
        try {
            libraries = libraryUrlsFromPaper(FoliaAccess.class.getClassLoader(),
                    context.getPluginSource());
        } catch (Exception e) {
            throw new IllegalStateException("FoliaAccess: nothing registered for "
                    + context.getConfiguration().getName()
                    + ". Call FoliaAccess.configure(classpath)...register() from your PluginLoader,"
                    + " or use FoliaAccess.from(context)...launch() in the bootstrapper.", e);
        }
        List<URL> urls = new ArrayList<>(libraries.size() + 1);
        try {
            urls.add(context.getPluginSource().toUri().toURL());
        } catch (java.net.MalformedURLException e) {
            throw new IllegalStateException("bad plugin source", e);
        }
        urls.addAll(libraries);
        return start(context, urls);
    }

    /**
     * Discovers library jars from the public resources exposed by Paper's
     * plugin classloader. It is deliberately data-driven: the only resource
     * name is the standard jar manifest, not a list of libraries or server
     * packages.
     * <p>
     * Paper's {@code PaperPluginClassLoader.getResources} exposes resources
     * from its own jar and its library loader. The library loader is parented
     * to the server classloader, so its resource enumeration also sees every
     * server jar. Subtracting the parent's manifest URLs and the plugin's own
     * source leaves the library jars registered with the builder. This uses
     * the public resource API rather than private field names or reflection.
     * <p>
     * As with any classloader-only discovery, jars need a standard
     * {@code META-INF/MANIFEST.MF} entry for their containing URL to be
     * enumerable. {@code register()} validates every resolved jar before
     * handing it to Paper; the integration test covers the Packetevents graph.
     */
    static List<URL> libraryUrlsFromPaper(ClassLoader paper, Path pluginSource) throws Exception {
        URL pluginJar = pluginSource.toUri().toURL();
        Set<Path> parentJars = jarsFromManifests(paper.getParent());
        Set<Path> paperJars = jarsFromManifests(paper);
        Path pluginPath = normalize(pluginJar);
        LinkedHashSet<Path> libraries = new LinkedHashSet<>();
        for (Path jar : paperJars) {
            if (jar.equals(pluginPath) || parentJars.contains(jar)) continue;
            libraries.add(jar);
        }
        List<URL> urls = new ArrayList<>(libraries.size());
        for (Path jar : libraries) urls.add(jar.toUri().toURL());
        return urls;
    }

    private static Set<Path> jarsFromManifests(ClassLoader loader) throws Exception {
        Set<Path> jars = new LinkedHashSet<>();
        if (loader == null) return jars;
        Enumeration<URL> resources = loader.getResources(JarFile.MANIFEST_NAME);
        while (resources.hasMoreElements()) {
            URL resource = resources.nextElement();
            if (!(resource.openConnection() instanceof JarURLConnection connection)) continue;
            jars.add(normalize(connection.getJarFileURL()));
        }
        return jars;
    }

    private static Path normalize(URL jar) throws Exception {
        return Path.of(jar.toURI()).toAbsolutePath().normalize();
    }

    /**
     * The loader path enumerates jars through their standard manifest
     * resources. Validate the precondition while registering, where an absent
     * manifest can produce an actionable error instead of silently omitting a
     * library later in {@code createPlugin}.
     */
    static void requireManifest(Path path) throws java.io.IOException {
        try (JarFile jar = new JarFile(path.toFile())) {
            if (jar.getManifest() == null) {
                throw new IllegalStateException("FoliaAccess's PluginLoader path requires "
                        + "a META-INF/MANIFEST.MF in each library jar: " + path
                        + ". Use the bootstrapper-only direct launch path for this artifact.");
            }
        }
    }

    private static JavaPlugin start(PluginProviderContext context, List<URL> urls) {
        String name = context.getConfiguration().getName();
        AccessClassLoader loader = null;
        Class<?> type = null;
        try {
            // The parent is the plugin's own PaperPluginClassLoader (this class
            // is loaded by it in the bootstrap phase). Reuse its dependency
            // access policy for AccessClassLoader before loading the main class;
            // Paper then sees it registered and does not use its unsafe fallback.
            loader = new AccessClassLoader(
                    urls.toArray(new URL[0]),
                    FoliaAccess.class.getClassLoader(),
                    context.getConfiguration(),
                    context.getPluginSource());
            if (loader.getParent() instanceof ConfiguredPluginClassLoader) {
                loader.registerAccessBackedGroup();
            }

            String main = context.getConfiguration().getMainClass();
            type = Class.forName(main, true, loader);
            if (type.getClassLoader() != loader) {
                // The parent won the race for the main class, so the libraries
                // are in a different loader after all -- exactly the bug this
                // exists to avoid. Fail here rather than at connection time.
                throw new IllegalStateException("main class was loaded by "
                        + type.getClassLoader() + " instead of FoliaAccess's loader");
            }
            // Resolve JavaPlugin through the plugin's OWN loader, so the check
            // is self-consistent. Comparing against JavaPlugin.class (this
            // class's copy) would be defeated by any difference between how
            // FoliaAccess's loader and the plugin's loader resolve JavaPlugin --
            // which is precisely the failure mode seen on some servers.
            Class<?> javaPlugin = Class.forName("org.bukkit.plugin.java.JavaPlugin", false, loader);
            if (!javaPlugin.isAssignableFrom(type)) {
                throw new IllegalStateException(main + " is not a JavaPlugin");
            }
            return (JavaPlugin) type.getDeclaredConstructor().newInstance();
        } catch (Throwable e) {
            if (loader != null) {
                try {
                    loader.close();
                } catch (Exception closeFailure) {
                    e.addSuppressed(closeFailure);
                }
                try {
                    loader.unregisterFromPaper();
                } catch (Exception unregisterFailure) {
                    e.addSuppressed(unregisterFailure);
                }
            }
            throw new IllegalStateException("FoliaAccess failed to start " + name
                    + (type == null ? "" : "\n" + diagnose(type, loader)), e);
        }
    }

    /**
     * Everything needed to see why a class-identity check failed: where the
     * main class, its superclass, and both copies of JavaPlugin were loaded.
     * Attached to any start failure, because "is not a JavaPlugin" without
     * this is unanswerable.
     */
    private static String diagnose(Class<?> type, ClassLoader loader) {
        Class<?> sup = type.getSuperclass();
        Class<?> jpViaPlugin = null;
        try {
            jpViaPlugin = Class.forName("org.bukkit.plugin.java.JavaPlugin", false, loader);
        } catch (Exception ignored) {
            // reported as "?" below
        }
        return "  main class                  : " + type.getName() + " @ " + type.getClassLoader() + "\n"
                + "  superclass                  : "
                + (sup == null ? "none" : sup.getName() + " @ " + sup.getClassLoader()) + "\n"
                + "  JavaPlugin via plugin loader: " + (jpViaPlugin == null ? "?" : jpViaPlugin.getClassLoader()) + "\n"
                + "  JavaPlugin via FoliaAccess     : " + JavaPlugin.class.getClassLoader() + "\n"
                + "  AccessClassLoader parent      : " + loader.getParent() + "\n"
                + "  same JavaPlugin class object: " + (jpViaPlugin != null && jpViaPlugin == JavaPlugin.class);
    }

    // ------------------------------------------------------------- builder

    public static final class Builder {

        private static final String CENTRAL = "https://repo1.maven.org/maven2/";

        private final PluginProviderContext context;
        /** Null when there is no PluginLoader (the from(context) path). */
        private final PluginClasspathBuilder classpathBuilder;
        private final List<String> repositories = new ArrayList<>(List.of(CENTRAL));
        private final Set<String> libraries = new LinkedHashSet<>();
        private final Set<String> exclusions = new LinkedHashSet<>();
        private Path cacheDirectory;

        private Builder(PluginClasspathBuilder classpath) {
            this.context = classpath.getContext();
            this.classpathBuilder = classpath;
        }

        private Builder(PluginProviderContext context) {
            this.context = context;
            this.classpathBuilder = null;
        }

        /**
         * Adds the libraries and repositories listed in
         * {@code paper-libraries.json} inside the plugin jar, reading it now.
         * <p>
         * Opt-in, and not a Paper format: that file is generated by the
         * {@code plugin-yml} Gradle plugin from {@code library(...)}
         * declarations when {@code generateLibrariesJson} is set. Reading it
         * by default would quietly tie this library to one build setup and do
         * nothing at all for a hand-written {@code paper-plugin.yml} or a
         * Maven build, so {@link #library(String)} remains the interface that
         * works everywhere.
         * <p>
         * There is no descriptor-based alternative to fall back on:
         * {@code PluginMeta} has no {@code getLibraries()} — that exists only
         * on {@code PluginDescriptionFile}, the legacy {@code plugin.yml}
         * type — and {@code paper-plugin.yml} has no {@code libraries} key.
         *
         * @throws IllegalStateException if the jar cannot be read or has no
         *                               such entry. Asking for a file that is
         *                               not there is a build misconfiguration,
         *                               and silently resolving nothing would
         *                               surface later as an unexplained
         *                               NoClassDefFoundError.
         */
        public Builder paperLibrariesJson() {
            Path source = context.getPluginSource();
            String text;
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(source.toFile())) {
                java.util.zip.ZipEntry entry = zip.getEntry("paper-libraries.json");
                if (entry == null) {
                    throw new IllegalStateException("paperLibrariesJson() was requested but "
                            + source + " contains no paper-libraries.json. Set "
                            + "generateLibrariesJson = true in the plugin-yml configuration, "
                            + "or declare libraries with library(...) instead.");
                }
                try (java.io.InputStream in = zip.getInputStream(entry)) {
                    text = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
            } catch (java.io.IOException e) {
                throw new IllegalStateException("could not read paper-libraries.json from "
                        + source, e);
            }
            Map<String, Object> root = Json.object(Json.parse(text));
            for (Object url : Json.object(root.get("repositories")).values()) {
                String value = Json.string(url);
                if (value != null) repository(value);
            }
            for (Object coordinates : Json.array(root.get("dependencies"))) {
                String value = Json.string(coordinates);
                if (value != null) libraries.add(value);
            }
            return this;
        }

        /** Adds a Maven repository. Central is always present. */
        public Builder repository(String url) {
            repositories.add(url.endsWith("/") ? url : url + "/");
            return this;
        }

        /**
         * Adds a repository as Aether models it, so declarations can be moved
         * across from {@code MavenLibraryResolver} unchanged.
         * <p>
         * Only the URL is used. Authentication, proxies and mirror settings
         * are not honoured — this downloads over plain HTTP(S) rather than
         * driving Aether, which is what keeps it dependency-free.
         */
        public Builder repository(RemoteRepository repository) {
            return repository(repository.getUrl());
        }

        /** Adds an artifact and its runtime dependencies, as {@code group:artifact:version}. */
        public Builder library(String coordinates) {
            libraries.add(coordinates);
            return this;
        }

        /**
         * Adds a dependency as Aether models it, so declarations can be moved
         * across from {@code MavenLibraryResolver} unchanged.
         * <p>
         * Scope and optionality are honoured the same way a POM's are: an
         * optional dependency, or one scoped anything other than
         * {@code compile}/{@code runtime}, is ignored. Its exclusions are
         * applied <b>globally</b> rather than to that branch alone — a
         * simplification worth knowing about, though
         * {@code exclude("io.netty")}-style use is unaffected by the
         * difference.
         */
        public Builder library(Dependency dependency) {
            if (dependency.isOptional()) return this;
            String scope = dependency.getScope();
            if (scope != null && !scope.isEmpty()
                    && !scope.equals("compile") && !scope.equals("runtime")) {
                return this;
            }
            org.eclipse.aether.artifact.Artifact artifact = dependency.getArtifact();
            libraries.add(artifact.getGroupId() + ":" + artifact.getArtifactId()
                    + ":" + artifact.getVersion());
            for (Exclusion exclusion : dependency.getExclusions()) {
                String group = exclusion.getGroupId();
                String name = exclusion.getArtifactId();
                exclusions.add(name == null || name.isEmpty() || name.equals("*")
                        ? group : group + ":" + name);
            }
            return this;
        }

        /**
         * Drops a {@code group} or {@code group:artifact} from the graph, with
         * its own dependencies. Use for anything the server already provides.
         */
        public Builder exclude(String groupOrCoordinates) {
            exclusions.add(groupOrCoordinates);
            return this;
        }

        /**
         * Where jars are cached. Defaults to {@code plugins/.foliaaccess},
         * shared between plugins so a common library downloads once.
         */
        public Builder cacheDirectory(Path directory) {
            this.cacheDirectory = directory;
            return this;
        }

        /**
         * Resolves the declared libraries and hands each jar to Paper's
         * {@link PluginClasspathBuilder}. Call from a {@code PluginLoader}.
         * <p>
         * Nothing is stored in FoliaAccess: a loader and a bootstrapper run in
         * different classloaders, so static state would not survive. Paper's
         * builder is the carrier; {@code launch()} discovers the libraries
         * from its public resource API.
         */
        public void register() {
            if (classpathBuilder == null) {
                throw new IllegalStateException("register() is for a PluginLoader. "
                        + "In a bootstrapper, use FoliaAccess.from(context).library(...).launch().");
            }
            List<URL> urls = classpath();
            URL pluginJar;
            try {
                pluginJar = context.getPluginSource().toUri().toURL();
            } catch (java.net.MalformedURLException e) {
                throw new IllegalStateException("bad plugin source", e);
            }
            for (URL url : urls) {
                if (url.sameFile(pluginJar)) continue;   // Paper adds the plugin jar itself
                try {
                    Path library = Path.of(url.toURI());
                    requireManifest(library);
                    classpathBuilder.addLibrary(new JarLibrary(library));
                } catch (Exception e) {
                    throw new IllegalStateException("FoliaAccess could not hand "
                            + url + " to Paper's classpath builder", e);
                }
            }
        }

        private Path cacheDirectory() {
            return cacheDirectory != null
                    ? cacheDirectory
                    : context.getDataDirectory().getParent().resolve(".foliaaccess");
        }

        /** Resolves, downloads and starts the plugin in one step. No PluginLoader. */
        public JavaPlugin launch() {
            return start(context, classpath());
        }

        private List<URL> classpath() {
            try {
                Path cache = cacheDirectory();
                Files.createDirectories(cache);

                Resolver resolver = new Resolver(repositories, exclusions, cache);
                List<URL> urls = new ArrayList<>();
                // The plugin jar first. Its classes must come from this loader
                // rather than Paper's copy, or the libraries end up in a
                // different one again and nothing has been gained.
                urls.add(context.getPluginSource().toUri().toURL());
                for (Resolver.Artifact artifact : resolver.resolve(new ArrayList<>(libraries))) {
                    // BOMs and other pom-packaged entries carry no jar.
                    if (!resolver.hasJar(artifact)) continue;
                    urls.add(resolver.jar(artifact).toUri().toURL());
                }
                return urls;
            } catch (Exception e) {
                throw new IllegalStateException("FoliaAccess could not resolve libraries for "
                        + context.getConfiguration().getName(), e);
            }
        }
    }
}
