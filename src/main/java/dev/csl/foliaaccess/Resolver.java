package dev.csl.foliaaccess;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Transitive dependency resolution without a dependency of its own.
 *
 * <h2>Gradle metadata first, POM second</h2>
 * Where a {@code .module} file exists it is authoritative, because it is the
 * only one of the two that distinguishes a compile-time dependency from a
 * runtime one. The difference is not academic:
 * {@code packetevents-netty-common} lists {@code io.netty:netty-all:4.1.72}
 * under {@code apiElements} but <em>not</em> {@code runtimeElements} — its POM
 * flattens that to {@code <scope>compile</scope>}, so a POM-only resolver
 * (Maven, Aether, and therefore Paper's own library loader) downloads Netty
 * 4.1 into the plugin and breaks against a server running 4.2. Reading the
 * module metadata gets this right by construction.
 * <p>
 * The POM path is the fallback for artifacts published without module
 * metadata. It honours {@code compile}/{@code runtime} scope, skips
 * {@code provided}/{@code test}/{@code system} and {@code optional}, and
 * follows {@code <parent>} for properties and {@code dependencyManagement}.
 */
final class Resolver {

    /** One resolved artifact. */
    record Artifact(String group, String name, String version) {
        String key() {
            return group + ":" + name;
        }

        String path() {
            return group.replace('.', '/') + "/" + name + "/" + version
                    + "/" + name + "-" + version;
        }

        @Override
        public String toString() {
            return group + ":" + name + ":" + version;
        }
    }

    private final List<String> repositories;
    private final Set<String> exclusions;
    private final Path cache;
    private final Map<String, String> downloadCache = new HashMap<>();

    Resolver(List<String> repositories, Set<String> exclusions, Path cache) {
        this.repositories = repositories;
        this.exclusions = exclusions;
        this.cache = cache;
    }

    /**
     * Walks the dependency graph breadth-first from {@code roots}.
     * <p>
     * First version seen for a {@code group:artifact} wins, which makes roots
     * and shallow dependencies authoritative over deep ones — the same
     * nearest-wins rule Maven uses, and predictable enough that pinning a
     * version as a root always takes effect.
     */
    Set<Artifact> resolve(List<String> roots) throws IOException {
        Map<String, Artifact> picked = new LinkedHashMap<>();
        Deque<Artifact> queue = new ArrayDeque<>();
        for (String coordinates : roots) queue.add(parse(coordinates));

        while (!queue.isEmpty()) {
            Artifact artifact = queue.poll();
            if (excluded(artifact) || picked.containsKey(artifact.key())) continue;
            picked.put(artifact.key(), artifact);
            for (Artifact dependency : dependenciesOf(artifact)) {
                if (!excluded(dependency) && !picked.containsKey(dependency.key())) {
                    queue.add(dependency);
                }
            }
        }
        return new LinkedHashSet<>(picked.values());
    }

    private boolean excluded(Artifact a) {
        return exclusions.contains(a.group()) || exclusions.contains(a.key());
    }

    /**
     * Whether an artifact actually ships a jar.
     * <p>
     * BOMs do not. {@code net.kyori:adventure-bom} turns up as an ordinary
     * dependency while being {@code <packaging>pom</packaging>}, so without
     * this check the download step would chase a jar that was never published
     * and fail the whole startup.
     */
    boolean hasJar(Artifact artifact) {
        String module = fetchText(artifact.path() + ".module");
        if (module != null) {
            try {
                for (Object v : Json.array(Json.object(Json.parse(module)).get("variants"))) {
                    Map<String, Object> variant = Json.object(v);
                    Map<String, Object> attributes = Json.object(variant.get("attributes"));
                    if (!"library".equals(Json.string(attributes.get("org.gradle.category")))) continue;
                    for (Object f : Json.array(variant.get("files"))) {
                        String name = Json.string(Json.object(f).get("name"));
                        if (name != null && name.endsWith(".jar")) return true;
                    }
                }
                return false;
            } catch (RuntimeException ignored) {
                // fall through to the POM
            }
        }
        String pom = fetchText(artifact.path() + ".pom");
        if (pom == null) return true;                 // no metadata: try anyway
        Element project = parseXml(pom);
        String packaging = project == null ? null : text(project, "packaging");
        return packaging == null || packaging.equals("jar") || packaging.equals("bundle");
    }

    static Artifact parse(String coordinates) {
        String[] parts = coordinates.split(":");
        if (parts.length != 3) {
            throw new IllegalArgumentException(
                    "expected group:artifact:version, got '" + coordinates + "'");
        }
        return new Artifact(parts[0], parts[1], parts[2]);
    }

    // ------------------------------------------------------------ metadata

    private List<Artifact> dependenciesOf(Artifact artifact) throws IOException {
        String module = fetchText(artifact.path() + ".module");
        if (module != null) {
            try {
                return fromModule(module);
            } catch (RuntimeException ignored) {
                // Malformed or an unexpected format version: fall through to
                // the POM rather than failing the whole resolve.
            }
        }
        String pom = fetchText(artifact.path() + ".pom");
        return pom == null ? List.of() : fromPom(pom, artifact);
    }

    /** Dependencies of the runtime variant of a Gradle module. */
    private List<Artifact> fromModule(String json) {
        Map<String, Object> root = Json.object(Json.parse(json));
        List<Artifact> out = new ArrayList<>();
        for (Object v : Json.array(root.get("variants"))) {
            Map<String, Object> variant = Json.object(v);
            Map<String, Object> attributes = Json.object(variant.get("attributes"));
            // java-runtime alone would also match sources and javadoc, which
            // are category=documentation.
            if (!"java-runtime".equals(Json.string(attributes.get("org.gradle.usage")))) continue;
            if (!"library".equals(Json.string(attributes.get("org.gradle.category")))) continue;

            for (Object d : Json.array(variant.get("dependencies"))) {
                Map<String, Object> dependency = Json.object(d);
                String group = Json.string(dependency.get("group"));
                String name = Json.string(dependency.get("module"));
                String version = Json.string(Json.object(dependency.get("version")).get("requires"));
                if (group != null && name != null && version != null) {
                    out.add(new Artifact(group, name, version));
                }
            }
            return out;      // one runtime library variant is enough
        }
        return out;
    }

    /** Dependencies of a POM, honouring scope, optional, parents and properties. */
    private List<Artifact> fromPom(String xml, Artifact self) throws IOException {
        Map<String, String> properties = new HashMap<>();
        Map<String, String> managed = new HashMap<>();
        List<Artifact> out = new ArrayList<>();

        String current = xml;
        Artifact at = self;
        for (int depth = 0; current != null && depth < 8; depth++) {
            Element project = parseXml(current);
            if (project == null) break;

            properties.putIfAbsent("project.version", at.version());
            properties.putIfAbsent("project.groupId", at.group());
            collectProperties(project, properties);
            collectManaged(project, managed, properties);
            if (depth == 0) collectDependencies(project, out, managed, properties);

            Element parent = child(project, "parent");
            if (parent == null) break;
            Artifact parentArtifact = new Artifact(
                    text(parent, "groupId"), text(parent, "artifactId"), text(parent, "version"));
            if (parentArtifact.group() == null || parentArtifact.version() == null) break;
            current = fetchText(parentArtifact.path() + ".pom");
            at = parentArtifact;
        }
        return out;
    }

    private void collectProperties(Element project, Map<String, String> into) {
        Element properties = child(project, "properties");
        if (properties == null) return;
        NodeList children = properties.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element e) {
                into.putIfAbsent(e.getTagName(), e.getTextContent().trim());
            }
        }
    }

    private void collectManaged(Element project, Map<String, String> into,
                                Map<String, String> properties) {
        Element management = child(project, "dependencyManagement");
        if (management == null) return;
        Element dependencies = child(management, "dependencies");
        if (dependencies == null) return;
        for (Element d : children(dependencies, "dependency")) {
            String group = interpolate(text(d, "groupId"), properties);
            String name = interpolate(text(d, "artifactId"), properties);
            String version = interpolate(text(d, "version"), properties);
            if (group != null && name != null && version != null) {
                into.putIfAbsent(group + ":" + name, version);
            }
        }
    }

    private void collectDependencies(Element project, List<Artifact> out,
                                     Map<String, String> managed,
                                     Map<String, String> properties) {
        Element dependencies = child(project, "dependencies");
        if (dependencies == null) return;
        for (Element d : children(dependencies, "dependency")) {
            if ("true".equals(text(d, "optional"))) continue;
            String scope = text(d, "scope");
            if (scope != null && !scope.equals("compile") && !scope.equals("runtime")) continue;

            String group = interpolate(text(d, "groupId"), properties);
            String name = interpolate(text(d, "artifactId"), properties);
            String version = interpolate(text(d, "version"), properties);
            if (version == null && group != null) version = managed.get(group + ":" + name);
            if (group != null && name != null && version != null && !version.startsWith("[")) {
                out.add(new Artifact(group, name, version));
            }
        }
    }

    private static String interpolate(String value, Map<String, String> properties) {
        if (value == null || !value.contains("${")) return value;
        String result = value;
        for (int i = 0; i < 5 && result.contains("${"); i++) {
            for (Map.Entry<String, String> e : properties.entrySet()) {
                result = result.replace("${" + e.getKey() + "}", e.getValue());
            }
        }
        return result.contains("${") ? null : result;
    }

    // ----------------------------------------------------------------- xml

    private static Element parseXml(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // No DTDs, no external entities: this parses bytes off the network.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            Document document = factory.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            return document.getDocumentElement();
        } catch (Exception e) {
            return null;
        }
    }

    private static Element child(Element parent, String name) {
        for (Element e : children(parent, name)) return e;
        return null;
    }

    private static List<Element> children(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        NodeList nodes = parent.getChildNodes();
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node instanceof Element e && e.getTagName().equals(name)) out.add(e);
        }
        return out;
    }

    private static String text(Element parent, String name) {
        Element e = child(parent, name);
        return e == null ? null : e.getTextContent().trim();
    }

    // ------------------------------------------------------------ transfer

    /** Downloads the jar for an artifact if it is not already cached. */
    Path jar(Artifact artifact) throws IOException {
        String fileName = artifact.name() + "-" + artifact.version() + ".jar";
        Path target = cache.resolve(artifact.group())
                .resolve(artifact.name()).resolve(artifact.version()).resolve(fileName);
        if (Files.isRegularFile(target) && Files.size(target) > 0) return target;

        Files.createDirectories(target.getParent());
        IOException last = null;
        for (String repository : repositories) {
            try {
                Path temporary = Path.of(target + ".part");
                try (InputStream in = open(repository + artifact.path() + ".jar")) {
                    Files.copy(in, temporary, StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                return target;
            } catch (IOException e) {
                last = e;
            }
        }
        throw new IOException("could not download " + artifact + " from " + repositories, last);
    }

    /** Fetches a small metadata file, or null if no repository has it. */
    private String fetchText(String path) {
        String cached = downloadCache.get(path);
        if (cached != null) return cached.isEmpty() ? null : cached;
        for (String repository : repositories) {
            try (InputStream in = open(repository + path)) {
                String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                downloadCache.put(path, body);
                return body;
            } catch (IOException ignored) {
                // try the next repository
            }
        }
        downloadCache.put(path, "");
        return null;
    }

    private static InputStream open(String url) throws IOException {
        URL target = URI.create(url).toURL();
        URLConnection connection = target.openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(60_000);
        connection.setRequestProperty("User-Agent", "FoliaAccess");
        return connection.getInputStream();
    }
}
