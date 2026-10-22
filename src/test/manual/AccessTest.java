package dev.csl.foliaaccess;

import io.papermc.paper.plugin.bootstrap.PluginProviderContext;
import io.papermc.paper.plugin.configuration.PluginMeta;
import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.library.ClassPathLibrary;
import io.papermc.paper.plugin.loader.library.LibraryStore;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Covers the real fix: a PluginLoader feeds Paper's own PluginClasspathBuilder
 * (register), Paper carries the jars into the built classloader, and
 * createPlugin reads them back out (libraryUrlsFromPaper). Nothing crosses the
 * classloader boundary as FoliaAccess state.
 *
 * <p>Regressions pinned here include:
 * <ol>
 *   <li>libraryUrlsFromPaper must not mistake the server's own classpath for
 *       libraries — it derives the jar set from standard manifest resources,
 *       subtracts resources visible from the parent, and excludes the plugin
 *       source. No library artifact list is built into the method.</li>
 *   <li>AccessClassLoader must use class origin, not a hard-coded package list,
 *       to share server-provided class identity while keeping the plugin and
 *       its bundled libraries in the same loader.</li>
 *   <li>start() must fully initialize the plugin (AccessClassLoader.init does
 *       what PaperPluginClassLoader.init does), and the instance must be
 *       instanceof the server's JavaPlugin even when a jar in the library list
 *       contains its own copy of that class.</li>
 * </ol>
 */
public class AccessTest {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        System.out.printf("  %-60s %s%n", what, ok ? "PASS" : "FAIL");
        if (ok) pass++; else fail++;
    }

    // ------------------------------------------------------------ fakes

    static PluginMeta meta(String name) {
        return (PluginMeta) Proxy.newProxyInstance(AccessTest.class.getClassLoader(),
                new Class<?>[]{PluginMeta.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getName" -> name;
                    case "getMainClass" -> "does.not.Exist";
                    case "getVersion" -> "1.0.0";
                    case "toString" -> "meta:" + name;
                    case "hashCode" -> name.hashCode();
                    case "equals" -> false;
                    default -> null;
                });
    }

    /** A meta that answers everything PluginDescriptionFile's constructor reads. */
    static PluginMeta fullMeta(String name, String mainClass) {
        return (PluginMeta) Proxy.newProxyInstance(AccessTest.class.getClassLoader(),
                new Class<?>[]{PluginMeta.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getName" -> name;
                    case "getMainClass" -> mainClass;
                    case "getVersion" -> "1.0.0";
                    case "getDescription" -> "test plugin";
                    case "getWebsite" -> "";
                    case "getLoggerPrefix" -> name;
                    case "getAPIVersion" -> "1.20";
                    case "getAuthors", "getContributors", "getProvidedPlugins",
                         "getPluginDependencies", "getPluginSoftDependencies",
                         "getLoadBeforePlugins", "getPermissions" -> List.of();
                    case "getLoadOrder" -> org.bukkit.plugin.PluginLoadOrder.STARTUP;
                    case "getPermissionDefault" -> org.bukkit.permissions.PermissionDefault.OP;
                    case "toString" -> "meta:" + name;
                    case "hashCode" -> name.hashCode();
                    case "equals" -> false;
                    default -> null;
                });
    }

    static PluginProviderContext context(String name, Path pluginJar, Path dataDir) {
        return context(name, pluginJar, dataDir, meta(name));
    }

    static PluginProviderContext context(String name, Path pluginJar, Path dataDir, PluginMeta meta) {
        return (PluginProviderContext) Proxy.newProxyInstance(AccessTest.class.getClassLoader(),
                new Class<?>[]{PluginProviderContext.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getConfiguration" -> meta;
                    case "getPluginSource" -> pluginJar;
                    case "getDataDirectory" -> dataDir;
                    case "toString" -> "ctx:" + name;
                    case "hashCode" -> name.hashCode();
                    case "equals" -> false;
                    default -> null;
                });
    }

    /** Installs a minimal Server into Bukkit's singleton, without setServer's build-info check. */
    static void mockBukkitServer(Path pluginsDir) throws Exception {
        // paper-api 1.20.6's JavaPlugin constructor also asks the server's
        // UnsafeValues for a lifecycle event manager; it only stores the result.
        org.bukkit.UnsafeValues unsafe = (org.bukkit.UnsafeValues) Proxy.newProxyInstance(
                AccessTest.class.getClassLoader(),
                new Class<?>[]{org.bukkit.UnsafeValues.class},
                (p, m, a) -> switch (m.getName()) {
                    case "toString" -> "unsafe:AccessTest";
                    case "hashCode" -> 43;
                    case "equals" -> false;
                    default -> null;
                });
        org.bukkit.Server server = (org.bukkit.Server) Proxy.newProxyInstance(
                AccessTest.class.getClassLoader(),
                new Class<?>[]{org.bukkit.Server.class},
                (p, m, a) -> switch (m.getName()) {
                    case "getPluginsFolder" -> pluginsDir.toFile();
                    case "getLogger" -> Logger.getLogger("AccessTest");
                    case "getName" -> "AccessTestServer";
                    case "getBukkitVersion" -> "1.20.6";
                    case "getUnsafe" -> unsafe;
                    case "toString" -> "server:AccessTest";
                    case "hashCode" -> 42;
                    case "equals" -> false;
                    default -> null;
                });
        Field f = Bukkit.class.getDeclaredField("server");
        f.setAccessible(true);
        f.set(null, server);
    }

    /** A PluginClasspathBuilder that records every jar path handed to it. */
    static final class RecordingBuilder {
        final List<Path> paths = new ArrayList<>();
        final PluginClasspathBuilder proxy;

        RecordingBuilder(PluginProviderContext context) {
            LibraryStore store = (LibraryStore) Proxy.newProxyInstance(
                    AccessClassLoader.class.getClassLoader(),
                    new Class<?>[]{LibraryStore.class},
                    (p, m, a) -> {
                        if (m.getName().equals("addLibrary")) paths.add((Path) a[0]);
                        return null;
                    });
            // Holder so the invocation handler can return the proxy itself.
            PluginClasspathBuilder[] self = new PluginClasspathBuilder[1];
            self[0] = (PluginClasspathBuilder) Proxy.newProxyInstance(
                    AccessClassLoader.class.getClassLoader(),
                    new Class<?>[]{PluginClasspathBuilder.class},
                    (p, m, a) -> {
                        if (m.getName().equals("getContext")) return context;
                        if (m.getName().equals("addLibrary")) {
                            ((ClassPathLibrary) a[0]).register(store);
                            return self[0];
                        }
                        return self[0];
                    });
            proxy = self[0];
        }
    }

    static Path jarWithManifest(Path file) throws Exception {
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(file))) {
            z.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            z.write("Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            z.putNextEntry(new ZipEntry("dummy.txt"));
            z.write("x".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        return file;
    }

    static Path jarWithoutManifest(Path file) throws Exception {
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(file))) {
            z.putNextEntry(new ZipEntry("dummy.txt"));
            z.write("x".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }
        return file;
    }

    /** Compiles one small class into {@code classesDir} (sources under classesDir/src). */
    static void compile(Path classesDir, String fqcn, String body) throws Exception {
        compile(classesDir, fqcn, "", body);
    }

    /** Compiles one small class, optionally with an {@code extends} clause. */
    static void compile(Path classesDir, String fqcn, String extendsClause, String body) throws Exception {
        int dot = fqcn.lastIndexOf('.');
        Path src = classesDir.resolve("src")
                .resolve(fqcn.substring(0, dot).replace('.', '/'))
                .resolve(fqcn.substring(dot + 1) + ".java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package " + fqcn.substring(0, dot) + ";\npublic class "
                + fqcn.substring(dot + 1)
                + (extendsClause.isEmpty() ? "" : " extends " + extendsClause)
                + " { " + body + " }");
        javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
        int rc = compiler.run(null, null, null,
                "-classpath", System.getProperty("java.class.path"),
                "-d", classesDir.toString(), src.toString());
        if (rc != 0) throw new IllegalStateException("javac failed for " + fqcn);
    }

    /**
     * Models Paper's order: server/application classes first, then the
     * registered library loader. It lets the classloader test prove that a
     * duplicate library class from Paper's isolated loader is replaced by the
     * AccessClassLoader copy, while a server-provided duplicate is delegated to the server.
     */
    static final class LibraryFallbackParent extends ClassLoader {
        private final ClassLoader server;
        private final ClassLoader libraries;

        LibraryFallbackParent(ClassLoader server, ClassLoader libraries) {
            super(null);
            this.server = server;
            this.libraries = libraries;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            try {
                return server.loadClass(name);
            } catch (ClassNotFoundException ignored) {
                return libraries.loadClass(name);
            }
        }
    }

    /** Jars a compiled-classes directory (everything except the src/ tree). */
    static Path jarDirectory(Path jar, Path classesDir) throws Exception {
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(jar))) {
            z.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            z.write("Manifest-Version: 1.0\n".getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
            try (var walk = Files.walk(classesDir)) {
                for (Path p : (Iterable<Path>) walk.filter(Files::isRegularFile)::iterator) {
                    String name = classesDir.relativize(p).toString().replace('\\', '/');
                    if (name.startsWith("src/")) continue;
                    z.putNextEntry(new ZipEntry(name));
                    z.write(Files.readAllBytes(p));
                    z.closeEntry();
                }
            }
        }
        return jar;
    }

    @SuppressWarnings("unchecked")
    static List<URL> classpathOf(Object builder) throws Exception {
        Method m = builder.getClass().getDeclaredMethod("classpath");
        m.setAccessible(true);
        return (List<URL>) m.invoke(builder);
    }

    static boolean noMutableStatics() {
        for (Field f : FoliaAccess.class.getDeclaredFields()) {
            if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
            if (java.lang.reflect.Modifier.isFinal(f.getModifiers())
                    && f.getType() == String.class) continue;
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------- tests

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("foliaaccess-test");
        Path pluginJar = jarWithManifest(tmp.resolve("plugin.jar"));
        Path dataDir = Files.createDirectories(tmp.resolve("plugins").resolve("TestPlugin"));
        PluginProviderContext ctx = context("TestPlugin", pluginJar, dataDir);

        System.out.println("register() feeds Paper's PluginClasspathBuilder");
        RecordingBuilder recorder = new RecordingBuilder(ctx);
        FoliaAccess.configure(recorder.proxy)
                .library("org.slf4j:slf4j-api:2.0.9")
                .register();
        check("only the resolved library is handed to Paper (no marker/sidecar jar)",
                recorder.paths.size() == 1
                        && recorder.paths.get(0).toString().contains("slf4j-api-2.0.9.jar"));
        check("plugin jar is NOT handed to its own library loader",
                recorder.paths.stream().noneMatch(p -> p.equals(pluginJar)));
        check("handed library exists on disk", Files.isRegularFile(recorder.paths.get(0)));

        System.out.println("\nlibraryUrlsFromPaper() derives registered libraries from resources");
        URLClassLoader emptyPaper = new URLClassLoader(new URL[0], null);
        check("empty loader gives an empty library list",
                FoliaAccess.libraryUrlsFromPaper(emptyPaper, pluginJar).isEmpty());
        Path noManifest = jarWithoutManifest(tmp.resolve("without-manifest.jar"));
        try {
            FoliaAccess.requireManifest(noManifest);
            check("loader path rejects a library without a manifest early", false);
        } catch (IllegalStateException e) {
            check("loader path rejects a library without a manifest early",
                    e.getMessage().contains("META-INF/MANIFEST.MF"));
        }

        System.out.println("\nround-trip: register -> Paper carries -> read back");
        // Simulate Paper's public resource shape: PaperPluginClassLoader
        // exposes its own plugin jar and library-loader resources; that
        // library loader also sees its server-classloader parent. The generic
        // resource difference must recover arbitrary library URLs without a
        // per-library list, and must subtract both plugin and server jars.
        Path arbitraryLibrary = jarWithManifest(tmp.resolve("unlisted-library.jar"));
        URL[] carried = new URL[recorder.paths.size() + 2];
        carried[0] = pluginJar.toUri().toURL();
        for (int i = 0; i < recorder.paths.size(); i++) {
            carried[i + 1] = recorder.paths.get(i).toUri().toURL();
        }
        carried[carried.length - 1] = arbitraryLibrary.toUri().toURL();
        Path serverApiJar = jarWithManifest(tmp.resolve("server-api.jar"));
        Path serverNettyJar = jarWithManifest(tmp.resolve("server-netty.jar"));
        URLClassLoader appLike = new URLClassLoader(
                new URL[]{serverApiJar.toUri().toURL(), serverNettyJar.toUri().toURL()}, null);
        URLClassLoader paperPluginLoader = new URLClassLoader(carried, appLike);
        List<URL> recovered = FoliaAccess.libraryUrlsFromPaper(paperPluginLoader, pluginJar);
        check("registered and arbitrary additional jars are discovered",
                recovered.size() == recorder.paths.size() + 1);
        check("recovered set has no baked-in dependency names",
                recovered.stream().anyMatch(u -> u.toString().contains("slf4j-api-2.0.9.jar"))
                        && recovered.stream().anyMatch(u -> u.toString().contains("unlisted-library.jar")));
        check("the plugin's own jar is excluded",
                recovered.stream().noneMatch(u -> u.toString().contains("plugin.jar")));
        check("the server's own classpath jars are NOT mistaken for libraries",
                recovered.stream().noneMatch(u -> u.toString().contains("server-api.jar")
                        || u.toString().contains("server-netty.jar")));

        System.out.println("\ndescriptor-driven libraries");
        Path descriptorJar = tmp.resolve("with-libs.jar");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(descriptorJar))) {
            zip.putNextEntry(new ZipEntry("paper-libraries.json"));
            zip.write(("{\"repositories\":{\"central\":\"https://repo1.maven.org/maven2/\"},"
                    + "\"dependencies\":[\"org.slf4j:slf4j-api:2.0.9\"]}")
                    .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        try {
            FoliaAccess.from(context("Missing", pluginJar, dataDir)).paperLibrariesJson();
            check("missing paper-libraries.json throws", false);
        } catch (IllegalStateException e) {
            check("missing paper-libraries.json throws", true);
            check("...and the message names the fix",
                    e.getMessage().contains("generateLibrariesJson"));
        }
        check("paperLibrariesJson() reads the file",
                classpathOf(FoliaAccess.from(context("WithLibs", descriptorJar, dataDir))
                        .paperLibrariesJson()).size() == 2);
        check("ignored unless asked for",
                classpathOf(FoliaAccess.from(context("Plain", descriptorJar, dataDir))).size() == 1);
        check("register() refuses a bootstrapper-only builder", registerRefused(ctx));

        System.out.println("\ncode-declared libraries");
        check("library() needs no descriptor",
                classpathOf(FoliaAccess.from(context("CodeOnly", pluginJar, dataDir))
                        .library("org.slf4j:slf4j-api:2.0.9")).size() == 2);

        System.out.println("\nAether Dependency / RemoteRepository");
        check("Dependency + RemoteRepository resolve",
                classpathOf(FoliaAccess.from(context("Aether", pluginJar, dataDir))
                        .repository(new org.eclipse.aether.repository.RemoteRepository.Builder(
                                "central", "default", "https://repo1.maven.org/maven2/").build())
                        .library(new org.eclipse.aether.graph.Dependency(
                                new org.eclipse.aether.artifact.DefaultArtifact(
                                        "org.slf4j:slf4j-api:2.0.9"), null))).size() == 2);
        check("optional Dependency is skipped",
                classpathOf(FoliaAccess.from(context("Opt", pluginJar, dataDir))
                        .library(new org.eclipse.aether.graph.Dependency(
                                new org.eclipse.aether.artifact.DefaultArtifact(
                                        "org.slf4j:slf4j-api:2.0.9"), "compile", true))).size() == 1);
        check("provided-scope Dependency is skipped",
                classpathOf(FoliaAccess.from(context("Prov", pluginJar, dataDir))
                        .library(new org.eclipse.aether.graph.Dependency(
                                new org.eclipse.aether.artifact.DefaultArtifact(
                                        "org.slf4j:slf4j-api:2.0.9"), "provided"))).size() == 1);
        check("Dependency exclusions are honoured",
                classpathOf(FoliaAccess.from(context("Excl", pluginJar, dataDir))
                        .library(new org.eclipse.aether.graph.Dependency(
                                new org.eclipse.aether.artifact.DefaultArtifact(
                                        "org.slf4j:slf4j-api:2.0.9"), null, false,
                                List.of(new org.eclipse.aether.graph.Exclusion(
                                        "org.slf4j", "*", "*", "*"))))).size() == 1);

        System.out.println("\nAccessClassLoader: server-provided classes come from the parent");
        // No package list anywhere — the rule is derived per class: a bundled
        // library class whose parent-copy is not our own jar resolves to the
        // parent's copy; everything else stays child-first.
        Path srvClasses = tmp.resolve("srv-classes");
        compile(srvClasses, "net.kyori.adventure.key.Key",
                "public static String side() { return \"server\"; }");
        compile(srvClasses, "com.example.PluginOwn",
                "public static String side() { return \"server\"; }");
        compile(srvClasses, "arbitrary.server.api.Type",
                "public static String side() { return \"server\"; }");
        Path serverJar = jarDirectory(tmp.resolve("server.jar"), srvClasses);
        Path libClasses = tmp.resolve("lib-classes");
        compile(libClasses, "net.kyori.adventure.key.Key",
                "public static String side() { return \"lib\"; }");
        compile(libClasses, "arbitrary.server.api.Type",
                "public static String side() { return \"library\"; }");
        compile(libClasses, "com.example.OnlyInLib",
                "public static String side() { return \"lib\"; }");
        Path libJar2 = jarDirectory(tmp.resolve("lib.jar"), libClasses);
        Path plugClasses2 = tmp.resolve("plug2-classes");
        compile(plugClasses2, "com.example.PluginOwn",
                "public static String side() { return \"plugin\"; }");
        Path pluginJar2 = jarDirectory(tmp.resolve("plugin2.jar"), plugClasses2);
        URLClassLoader server = new URLClassLoader(
                new URL[]{serverJar.toUri().toURL()}, null);
        URLClassLoader paperLibraries = new URLClassLoader(
                new URL[]{libJar2.toUri().toURL()}, server);
        ClassLoader parent = new LibraryFallbackParent(server, paperLibraries);
        AccessClassLoader boot = new AccessClassLoader(
                new URL[]{pluginJar2.toUri().toURL(), libJar2.toUri().toURL()},
                parent, fullMeta("Deleg", "does.not.Exist"), pluginJar2);
        Class<?> key = boot.loadClass("net.kyori.adventure.key.Key");
        check("bundled class the server also provides resolves to the server's copy",
                key.getClassLoader() == server
                        && "server".equals(key.getDeclaredMethod("side").invoke(null)));
        Class<?> arbitraryApi = boot.loadClass("arbitrary.server.api.Type");
        check("same delegation works for an unlisted package",
                arbitraryApi.getClassLoader() == server
                        && "server".equals(arbitraryApi.getDeclaredMethod("side").invoke(null)));
        Class<?> onlyInLib = boot.loadClass("com.example.OnlyInLib");
        check("bundled class the server lacks stays child-first",
                onlyInLib.getClassLoader() == boot
                        && "lib".equals(onlyInLib.getDeclaredMethod("side").invoke(null)));
        Class<?> pluginOwn = boot.loadClass("com.example.PluginOwn");
        check("the plugin's own classes stay child-first even when the parent has a copy",
                pluginOwn.getClassLoader() == boot
                        && "plugin".equals(pluginOwn.getDeclaredMethod("side").invoke(null)));
        try {
            boot.loadClass("net.kyori.adventure.nbt.BinaryTag");
            check("unknown class still throws ClassNotFoundException", false);
        } catch (ClassNotFoundException expected) {
            check("unknown class still throws ClassNotFoundException", true);
        }

        System.out.println("\nstart() creates and initializes the plugin (end to end)");
        // A plugin main class, compiled against the real paper-api.
        Path plugClasses = tmp.resolve("plug-classes");
        compile(plugClasses, "test.MainPlugin", "org.bukkit.plugin.java.JavaPlugin", "");
        Path mainPluginJar = jarDirectory(tmp.resolve("main-plugin.jar"), plugClasses);
        // Bait: a jar in the library list that carries its own (empty) JavaPlugin —
        // exactly what the server classpath used to contribute via the read-back.
        Path baitClasses = tmp.resolve("bait-classes");
        compile(baitClasses, "org.bukkit.plugin.java.JavaPlugin", "");
        Path baitJar = jarDirectory(tmp.resolve("bait-server.jar"), baitClasses);
        mockBukkitServer(tmp.resolve("plugins"));
        PluginMeta startMeta = fullMeta("StartPlugin", "test.MainPlugin");
        PluginProviderContext startCtx = context("StartPlugin", mainPluginJar, dataDir, startMeta);
        Method start = FoliaAccess.class.getDeclaredMethod("start", PluginProviderContext.class, List.class);
        start.setAccessible(true);
        try {
            JavaPlugin plugin = (JavaPlugin) start.invoke(null, startCtx,
                    List.of(mainPluginJar.toUri().toURL(), baitJar.toUri().toURL()));
            check("plugin instance is the server's JavaPlugin (bait jar ignored)",
                    plugin instanceof JavaPlugin);
            check("plugin class was defined by AccessClassLoader",
                    plugin.getClass().getClassLoader() instanceof AccessClassLoader);
            check("JavaPlugin identity is shared with the server",
                    Class.forName("org.bukkit.plugin.java.JavaPlugin", false,
                            plugin.getClass().getClassLoader()) == JavaPlugin.class);
            check("plugin is initialized (description)",
                    plugin.getDescription() != null
                            && "StartPlugin".equals(plugin.getDescription().getName()));
            check("plugin is initialized (meta)", plugin.getPluginMeta() == startMeta);
            check("data folder is under the plugins folder",
                    plugin.getDataFolder().equals(tmp.resolve("plugins").resolve("StartPlugin").toFile()));
        } catch (Exception e) {
            e.printStackTrace();
            check("start() threw: " + e.getCause(), false);
        }

        System.out.println("\nno mutable static state");
        check("FoliaAccess holds no mutable statics", noMutableStatics());

        System.out.printf("%nPASS=%d  FAIL=%d%n", pass, fail);
        if (fail > 0) System.exit(1);
    }

    /** register() on a from(context) builder (no PluginLoader) must refuse. */
    static boolean registerRefused(PluginProviderContext ctx) {
        try {
            FoliaAccess.from(ctx).library("org.slf4j:slf4j-api:2.0.9").register();
            return false;
        } catch (IllegalStateException e) {
            return e.getMessage().contains("PluginLoader");
        }
    }
}
