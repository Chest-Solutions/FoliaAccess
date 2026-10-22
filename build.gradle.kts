plugins {
    id("java")
    id("java-library")
    id("maven-publish")
}

group = "dev.csl"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Paper only, and compileOnly. Nothing is bundled: this library must stay
    // dependency-free, because it is implementation()'d into the consuming
    // plugin's jar.
    //
    // Aether (org.eclipse.aether.graph.Dependency, RemoteRepository) comes in
    // transitively -- paper-api declares maven-resolver-provider at compile
    // scope -- so those types can appear in this API without adding a
    // dependency of our own, and are present at runtime on any Paper server.
    compileOnly("io.papermc.paper:paper-api:1.20.6-R0.1-SNAPSHOT")
}

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
    withSourcesJar()
}

// The tests in src/test/manual are plain main() programs, not JUnit — they
// exercise the real resolution and classloader paths, and ResolverTest needs
// network access. Run them with: ./gradlew runManualTests
sourceSets {
    create("manualTest") {
        java.srcDir("src/test/manual")
        resources.srcDir("src/test/manual-resources")
    }
}

dependencies {
    "manualTestImplementation"(sourceSets["main"]!!.output)
    "manualTestImplementation"("io.papermc.paper:paper-api:1.20.6-R0.1-SNAPSHOT")
}

tasks.register<JavaExec>("runAccessTest") {
    group = "verification"
    description = "Runs AccessTest (needs network: resolves slf4j-api from Maven Central)."
    dependsOn("compileManualTestJava")
    classpath = sourceSets["manualTest"]!!.runtimeClasspath
    mainClass.set("dev.csl.foliaaccess.AccessTest")
}

tasks.register<JavaExec>("runResolverTest") {
    group = "verification"
    description = "Runs ResolverTest (needs network)."
    dependsOn("compileManualTestJava")
    classpath = sourceSets["manualTest"]!!.runtimeClasspath
    mainClass.set("dev.csl.foliaaccess.ResolverTest")
}

tasks.register("runManualTests") {
    group = "verification"
    description = "Runs all manual tests."
    dependsOn("runAccessTest", "runResolverTest")
}

publishing {
    publications { create<MavenPublication>("maven") { from(components["java"]) } }
}
