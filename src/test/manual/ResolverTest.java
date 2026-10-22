package dev.csl.foliaaccess;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.jar.JarFile;

/**
 * Resolves real artifacts against real repositories. The thing being proved is
 * that reading Gradle module metadata keeps Netty out while keeping
 * adventure-nbt in — the exact pair that a POM-only resolver gets wrong.
 */
public class ResolverTest {

    static int pass = 0, fail = 0;

    static void check(String what, boolean ok) {
        System.out.printf("  %-58s %s%n", what, ok ? "PASS" : "FAIL");
        if (ok) pass++; else fail++;
    }

    public static void main(String[] args) throws Exception {
        Path cache = Files.createTempDirectory("foliaaccess-test");
        List<String> repos = List.of(
                "https://repo1.maven.org/maven2/",
                "https://repo.codemc.io/repository/maven-releases/");

        System.out.println("resolving com.github.retrooper:packetevents-spigot:2.14.0");
        Resolver resolver = new Resolver(repos, Set.of(), cache);
        Set<Resolver.Artifact> resolved =
                resolver.resolve(List.of("com.github.retrooper:packetevents-spigot:2.14.0"));
        resolved.forEach(a -> System.out.println("    " + a));

        boolean netty = resolved.stream().anyMatch(a -> a.group().equals("io.netty"));
        check("no io.netty (POM says compile, module says api-only)", !netty);
        check("packetevents-api present",
                resolved.stream().anyMatch(a -> a.name().equals("packetevents-api")));
        check("packetevents-netty-common present",
                resolved.stream().anyMatch(a -> a.name().equals("packetevents-netty-common")));
        check("adventure-nbt present (server does not expose it)",
                resolved.stream().anyMatch(a -> a.name().equals("adventure-nbt")));
        check("transitive depth reached (>3 artifacts)", resolved.size() > 3);

        System.out.println("\nloader-path manifest compatibility");
        List<String> jarsWithoutManifest = new ArrayList<>();
        int jarCount = 0;
        for (Resolver.Artifact artifact : resolved) {
            if (!resolver.hasJar(artifact)) continue;
            Path artifactJar = resolver.jar(artifact);
            jarCount++;
            try (JarFile jarFile = new JarFile(artifactJar.toFile())) {
                if (jarFile.getManifest() == null) jarsWithoutManifest.add(artifact.toString());
            }
        }
        check("checked the resolved Packetevents jar artifacts", jarCount > 0);
        check("every resolved jar has a manifest resource for generic read-back",
                jarsWithoutManifest.isEmpty());
        if (!jarsWithoutManifest.isEmpty()) {
            System.out.println("    missing manifest: " + jarsWithoutManifest);
        }

        System.out.println("\nFoliaShops library graph manifest compatibility");
        List<String> shopsRoots = List.of(
                "org.incendo:cloud-annotations:2.1.0",
                "org.incendo:cloud-minecraft-extras:2.0.0",
                "com.github.retrooper:packetevents-spigot:2.14.0",
                "org.incendo:cloud-paper:2.0.0");
        Resolver shopsResolver = new Resolver(repos, Set.of(), cache);
        Set<Resolver.Artifact> shopsLibraries = shopsResolver.resolve(shopsRoots);
        List<String> shopsWithoutManifest = new ArrayList<>();
        int shopsJarCount = 0;
        for (Resolver.Artifact artifact : shopsLibraries) {
            if (!shopsResolver.hasJar(artifact)) continue;
            Path artifactJar = shopsResolver.jar(artifact);
            shopsJarCount++;
            try (JarFile jarFile = new JarFile(artifactJar.toFile())) {
                if (jarFile.getManifest() == null) shopsWithoutManifest.add(artifact.toString());
            }
        }
        check("resolved FoliaShops' real library roots", shopsJarCount > 0);
        check("all FoliaShops library jars have a standard manifest",
                shopsWithoutManifest.isEmpty());
        if (!shopsWithoutManifest.isEmpty()) {
            System.out.println("    missing manifest: " + shopsWithoutManifest);
        }

        System.out.println("\nexclusions");
        Resolver excluded = new Resolver(repos, Set.of("net.kyori"), cache);
        Set<Resolver.Artifact> withoutKyori =
                excluded.resolve(List.of("com.github.retrooper:packetevents-spigot:2.14.0"));
        check("excluding a group drops it",
                withoutKyori.stream().noneMatch(a -> a.group().equals("net.kyori")));
        check("but keeps everything else",
                withoutKyori.stream().anyMatch(a -> a.name().equals("packetevents-api")));

        System.out.println("\nPOM-only artifact (no .module published)");
        Resolver pomOnly = new Resolver(repos, Set.of(), cache);
        Set<Resolver.Artifact> vault =
                pomOnly.resolve(List.of("org.yaml:snakeyaml:2.2"));
        vault.forEach(a -> System.out.println("    " + a));
        check("POM fallback resolves at least the root", !vault.isEmpty());

        System.out.println("\ndownload + cache");
        Resolver.Artifact api = resolved.stream()
                .filter(a -> a.name().equals("packetevents-api")).findFirst().orElseThrow();
        Path jar = resolver.jar(api);
        check("jar downloaded", Files.isRegularFile(jar) && Files.size(jar) > 1000);
        long before = Files.getLastModifiedTime(jar).toMillis();
        Path again = resolver.jar(api);
        check("second call is cached, not re-downloaded",
                again.equals(jar) && Files.getLastModifiedTime(jar).toMillis() == before);
        check("no .part files left behind",
                Files.walk(cache).noneMatch(p -> p.toString().endsWith(".part")));

        System.out.println("\nBOM / pom-packaging handling");
        Resolver.Artifact bom = resolved.stream()
                .filter(a -> a.name().endsWith("-bom")).findFirst().orElse(null);
        check("a BOM was reached during resolution", bom != null);
        if (bom != null) check("BOM reported as having no jar", !resolver.hasJar(bom));
        check("real artifact reported as having a jar", resolver.hasJar(api));

        System.out.println("\nmalformed input");
        try {
            Resolver.parse("not-a-coordinate");
            check("bad coordinates rejected", false);
        } catch (IllegalArgumentException e) {
            check("bad coordinates rejected", true);
        }

        System.out.printf("%nPASS=%d  FAIL=%d%n", pass, fail);
        if (fail > 0) System.exit(1);
    }
}
