# FoliaAccess

A ~26 KB, zero-dependency replacement for Paper's plugin library loader.

It runs your plugin in a classloader that holds **the plugin jar and its
libraries together**, instead of the isolated one Paper builds. That one
difference is the whole library.

## The problem it solves

Paper's library mechanism exposes a single hook:

```java
public interface LibraryStore { void addLibrary(Path path); }
```

You hand it paths; Paper builds the classloader. That loader sits *below* the
plugin and has no view of other plugins — `join-classpath` does not reach it,
because it isn't part of the plugin dependency graph.

For most libraries that is fine. It breaks the moment a library resolves
another *plugin* by class reference, because the probe and the link happen on
different loaders:

```java
// PacketEvents, ViaVersionUtil.load()
ClassLoader cl = PacketEvents.getAPI().getPlugin().getClass().getClassLoader();
cl.loadClass("com.viaversion.viaversion.api.Via");   // plugin classloader  -> found
viaVersionAccessor = new ViaVersionAccessorImpl();

// ViaVersionAccessorImpl:57
Via.getManager();                                     // library classloader -> NoClassDefFoundError
```

The probe succeeds, the call fails, and the connection dies in the netty
pipeline. Shading fixes it by putting the library in the plugin jar; this
fixes it at runtime without the jar bloat.

## Use

`implementation` it, so it lands in your plugin jar — it cannot do its job
from a library classloader, which is the very thing it exists to avoid:

```kotlin
dependencies {
    implementation("dev.csl:FoliaAccess:1.0.0")
    compileOnly("com.github.retrooper:packetevents-spigot:2.14.0")  // not library(...)
}
```

Everything happens in the bootstrapper:

```java
public final class MyBootstrap implements PluginBootstrap {
    @Override public void bootstrap(BootstrapContext context) { }

    @Override public JavaPlugin createPlugin(PluginProviderContext context) {
        return FoliaAccess.from(context)
                .repository("https://repo.codemc.io/repository/maven-releases/")
                .library("com.github.retrooper:packetevents-spigot:2.14.0")
                .launch();
    }
}
```

```kotlin
paper {
    main = "com.example.MyPlugin"
    bootstrapper = "com.example.MyBootstrap"
}
```

Remove the matching `library(...)` entries from Gradle, and any `loader = `
pointing at a `PluginLoader` that was resolving them.

### How the PluginLoader path works (and why it is not a static handoff)

Paper runs a `PluginLoader` and a `PluginBootstrap` in **different
loaders**. `PaperPluginProviderFactory` loads the `loader:` class with a
standalone `PaperSimplePluginClassLoader` and closes it immediately;
`PaperPluginParent` loads the bootstrapper with the `PaperPluginClassLoader`
(`PaperPluginClassLoader extends PaperSimplePluginClassLoader`). Both are
parented to Paper's own classloader, so they are siblings — a class bundled in
the plugin jar, FoliaAccess included, is loaded **twice**, and static state
cannot carry from one phase to the other. That is not fixable from inside a
bundled library; any loader→bootstrap handoff would have to smuggle state
across the boundary.

So FoliaAccess does not attempt one. The declaration travels through Paper's own
machinery, the one channel both phases genuinely share:

1. The `PluginLoader` resolves the libraries and adds each jar to Paper's
   `PluginClasspathBuilder` — `register()` does exactly this, and stores
   nothing in FoliaAccess.
2. Paper builds the plugin's classloader with those jars in its library loader.
3. `createPlugin` discovers the library jars from the public resources Paper
   exposes on that classloader and builds the combined classloader. It subtracts
   the plugin jar and the resources already visible from the parent, so server
   jars do not leak into FoliaAccess's child-first loader.

```java
public final class MyLoader implements PluginLoader {
    @Override public void classloader(PluginClasspathBuilder classpath) {
        FoliaAccess.configure(classpath)
                .library("com.github.retrooper:packetevents-spigot:2.14.0")
                .register();
    }
}

public final class MyBootstrap implements PluginBootstrap {
    @Override public void bootstrap(BootstrapContext context) { }
    @Override public JavaPlugin createPlugin(PluginProviderContext context) {
        return FoliaAccess.launch(context);
    }
}
```

`libraryUrlsFromPaper` is data-driven: it enumerates the standard
`META-INF/MANIFEST.MF` resource and maps each `JarURLConnection` back to its
containing jar. It does not name any dependency jars, packages, or server jars.
The plugin source is excluded using `context.getPluginSource()`; jars already
visible from the Paper classloader's parent are subtracted. That second step is
essential: Paper's library loader is parented to the server classloader, and
`getResources` includes the parent's resources, so a plain enumeration would
pull server jars into the child-first loader and fork Bukkit class identity.

The `PluginLoader` path requires a manifest entry in each library jar, because
that is the public resource anchor available for generic classpath discovery.
FoliaAccess validates this at registration and fails early with the offending jar
path. The bootstrapper-only direct `from(context)…launch()` path does not rely
on Paper resource enumeration. No sidecar/marker jar, temp-file handoff,
system property, reflection into Paper fields, or package-prefix list is used.

The bootstrapper-only path (`FoliaAccess.from(context).library(...).launch()`)
remains for plugins with no `PluginLoader`; it resolves directly and does not
touch Paper's builder.

### Aether types

`Dependency` and `RemoteRepository` are accepted directly, so declarations
move across from `MavenLibraryResolver` unchanged:

```java
FoliaAccess.configure(classpath)
        .repository(new RemoteRepository.Builder(
                "codemc", "default", "https://repo.codemc.io/repository/maven-releases/").build())
        .library(new Dependency(new DefaultArtifact(
                "com.github.retrooper:packetevents-spigot:2.14.0"), null))
        .register();
```

No dependency is added by this: paper-api declares `maven-resolver-provider`
at compile scope, so Aether is on the compile classpath already and present
at runtime on any Paper server.

A `Dependency` that is optional, or scoped anything other than
`compile`/`runtime`, is ignored, as in a POM. Its exclusions are applied
globally rather than to that one branch — worth knowing, though
`exclude("io.netty")`-style use is unaffected. Only a `RemoteRepository`'s URL
is read; authentication, proxies and mirrors are not honoured, because this
downloads over plain HTTP(S) rather than driving Aether.

### Reading libraries from the jar is opt-in

If you use the `plugin-yml` Gradle plugin with `generateLibrariesJson`, the
coordinates and repositories are already in `paper-libraries.json`, and
`.paperLibrariesJson()` reads them so nothing is repeated in Java.

It is **off by default**. That file is a convention of that one Gradle
plugin, not a Paper format: a hand-written `paper-plugin.yml` or a Maven
build has nothing to read, so relying on it would quietly exclude them.
`.library(...)` is the interface that works everywhere.

When you do ask for it and the file is absent, that **throws**. Asking for a
file that is not there is a build misconfiguration, and resolving nothing
instead would only resurface later as an unexplained `NoClassDefFoundError`.

There is also no descriptor-based alternative to reach for. `PluginMeta` has
no `getLibraries()` — that exists only on `PluginDescriptionFile`, the legacy
`plugin.yml` type — and `paper-plugin.yml` has no `libraries` key at all.

## Dependencies: transitive, Gradle metadata first

You name the roots; their runtime dependencies come along.

Where a `.module` file exists it wins, because it is the only one of the two
formats that separates a compile-time dependency from a runtime one. That
difference is the whole reason this library exists:

| | `packetevents-netty-common` declares Netty |
|---|---|
| `.module` → `runtimeElements` | **no** (it is in `apiElements` only) |
| `.pom` | `<scope>compile</scope>` — **yes** |

Maven, Aether, and therefore Paper's own library loader only read the POM, so
they download Netty 4.1.72 into your plugin and break it against a server on
4.2. Reading module metadata gets this right by construction, with no manual
exclusions.

The POM path is the fallback for artifacts published without module metadata.
It honours `compile`/`runtime` scope, skips `provided`/`test`/`system` and
`optional`, and follows `<parent>` for properties and `dependencyManagement`.
BOMs and other `pom`-packaged entries are traversed but never downloaded.

Nearest-wins on version conflicts, so pinning an artifact as a root always
takes effect. `exclude("group")` or `exclude("group:artifact")` drops a
subtree for anything the server already provides.

Jars are cached in `plugins/.foliaaccess/<group>/<artifact>/<version>/`, shared
between plugins, downloaded through a `.part` file so an interrupted download
cannot leave a truncated jar that later looks valid.

## How it works

`FoliaAccess.launch()` builds a `URLClassLoader` over `[plugin jar, libraries…]`
whose parent is Paper's plugin classloader, then loads your main class from it.

* **Platform classes are derived, not listed.** The platform classloader is
  queried first for JDK/platform-module types; no `java.*`/`javax.*` prefix list
  is maintained.
* **Plugin classes are child-first.** The plugin jar is identified by its
  source URL, so its classes are defined here rather than returned from Paper's
  duplicate copy.
* **Library classes use class origin, not package prefixes.** If a class is in
  one of this loader's libraries, the parent is asked for it. If the parent's
  definition came from one of the same jars, FoliaAccess defines its own copy so
  the plugin and library share a loader. Otherwise the parent's class wins,
  preserving identity for server and other-plugin APIs. Origin is checked from
  the class's `CodeSource`, then resource URL/URLClassLoader URLs when needed.
* **Other plugins via the parent.** The parent is the plugin's own
  `PaperPluginClassLoader`, which has a `PluginClassLoaderGroup`. Delegating to
  `parent.loadClass` reaches that group, so declared plugin dependencies
  (ViaVersion, with `join-classpath`) resolve. FoliaAccess does not self-register
  as a peer loader; Paper's plugin manager may add configured loaders to its
  legacy/global group during enable, which can emit a warning for this custom
  loader.

It implements `ConfiguredPluginClassLoader` because `JavaPlugin`'s constructor
demands it:

```java
if (cl instanceof ConfiguredPluginClassLoader ccl) ccl.init(this);
else throw new IllegalStateException("JavaPlugin requires to be created by a valid classloader.");
```

`launch()` asserts the main class really was defined by its own loader, so a
silent fallback to the parent surfaces as a startup error rather than the
original `NoClassDefFoundError` at connection time.

## Caveats

* **`ConfiguredPluginClassLoader` and `PluginClassLoaderGroup` are Paper
  provider APIs.** They live in `paper-api` and are implementable, but they are
  lower-level than normal plugin API and may change between Paper versions.
* **The provided startup integration targets modern Paper plugins.** It uses
  `paper-plugin.yml`'s `bootstrapper:` (and, for libraries registered through
  Paper, the `loader:` hook). Paper 26.2 also routes the legacy
  `org.bukkit.plugin.java.PluginClassLoader` through
  `ConfiguredPluginClassLoader`, and legacy `plugin.yml` can declare a
  `paper-plugin-loader`. That hook can contribute library paths, but the legacy
  provider still constructs the main plugin itself; it does not expose the
  modern bootstrapper's `createPlugin` hook that FoliaAccess currently uses.
  So the shared classloader interface is not the blocker, but this release does
  not yet provide a legacy `plugin.yml` startup adapter.
* **Manifest required for the PluginLoader path.** The standard manifest is
  the public resource anchor used to discover arbitrary jar URLs. Registration
  checks it before handing a jar to Paper; use direct bootstrapper resolution
  for an artifact without one.
* **Your plugin's classes are loaded twice if something else asks Paper's
  loader for them.** Returning your instance from `createPlugin` keeps the
  normal path clean, but another plugin reaching in through the group gets
  Paper's copy.
* **No checksum verification.** Artifacts are trusted from the repositories you
  list; pin versions and use repositories you trust.

## License

[MIT](LICENSE) © 2026 Chest Solutions
