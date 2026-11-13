plugins {
    java
}

group = "core.amazake"
version = "1.0.0"
description = "Reproducible heavy-load benchmark plugin for Paper-based 1.21.11 servers"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Public Paper API only: the plugin must run on any Paper-based server (Paper, Leaf, Amazake, ...).
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-serial", "-parameters"))
}

tasks.processResources {
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(props)
    }
}

tasks.jar {
    archiveBaseName.set("AmazakeStress")
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("")
}

// Unit tests of the pure parts (no server needed), e.g. the reflection bridge to Amazake's EntityTickStats.
dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}

tasks.test {
    useJUnitPlatform()
}
