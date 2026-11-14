// AmazakeCore — a feature-module system packaged as an obfuscated AmazakeCore.azs.
//   ./gradlew -p amazake-core-system azs            -> build/libs/AmazakeCore.azs (obfuscated)
//   ./gradlew -p amazake-core-system azs -PnoObfuscate   -> the plain (readable) build, for debugging
//   -PamazakeApiJar=/path/to/amazake-systems-api.jar    -> build against that API jar
// H2 (pure Java) is bundled inside the .azs; Paper and the systems API are provided by the server.
import java.nio.file.Files

plugins {
    java
}

val systemName = rootProject.name            // AmazakeCore
group = "core.amazake"
version = "1.0.0"

val noObfuscate = project.hasProperty("noObfuscate")

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

// The systems API jar: -PamazakeApiJar, else the one the server build produced.
val apiJar: File = (findProperty("amazakeApiJar") as String?)?.let { file(it) }
    ?: fileTree(rootDir.parentFile.resolve("amazake-server/build/libs")) {
        include("amazake-systems-api-*.jar")
        exclude("*-sources.jar")
    }.files.minByOrNull { it.name.length }
    ?: throw GradleException(
        "Нет jar API систем. Соберите его: ./gradlew :amazake-server:systemsApiJar, " +
            "или укажите -PamazakeApiJar=/путь/amazake-systems-api.jar")

configurations.create("bundled")

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly(files(apiJar))
    // Bundled into the .azs (pure Java, no native library: the system reloads cleanly).
    val h2 = "com.h2database:h2:2.3.232"
    compileOnly(h2)
    add("bundled", h2)

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    testImplementation(files(apiJar))
    testImplementation("com.h2database:h2:2.3.232")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
    options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-serial", "-parameters"))
}

tasks.processResources {
    val props = mapOf("name" to systemName, "version" to project.version.toString())
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("system.yml") { expand(props) }
}

tasks.test {
    useJUnitPlatform()
}

// 1) A fat jar: our classes + resources + the bundled H2 driver.
val fatAzs = tasks.register<Jar>("fatAzs") {
    archiveFileName = "AmazakeCore-fat.jar"
    destinationDirectory = layout.buildDirectory.dir("tmp")
    from(sourceSets.main.get().output)
    from(configurations["bundled"].map { if (it.isDirectory) it else zipTree(it) })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// 2) ProGuard obfuscates our own classes (the H2 library and the entry points are kept). Run as a plain tool so the
//    build needs no extra Gradle plugin.
val proguard = configurations.create("proguard")
dependencies { add("proguard", "com.guardsquare:proguard-base:7.6.1") }

val obfuscatedAzs = tasks.register<JavaExec>("obfuscatedAzs") {
    dependsOn(fatAzs)
    val inJar = fatAzs.get().archiveFile.get().asFile
    // ProGuard picks the output kind from the extension: .jar => archive, anything else => a directory.
    // So write a .jar, then copy it to the .azs below.
    val outTmp = layout.buildDirectory.file("tmp/AmazakeCore-proguard.jar").get().asFile
    val outAzs = layout.buildDirectory.file("libs/$systemName.azs").get().asFile
    val rules = file("proguard-rules.pro")
    inputs.file(inJar)
    inputs.file(rules)
    outputs.file(outAzs)
    classpath = proguard
    mainClass = "proguard.ProGuard"
    doFirst {
        outTmp.parentFile.mkdirs()
        outAzs.parentFile.mkdirs()
        Files.deleteIfExists(outTmp.toPath())
        Files.deleteIfExists(outAzs.toPath())
        // Library jars: the JDK modules plus Paper + the systems API (referenced, not bundled).
        val javaHome = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
            .get().metadata.installationPath.asFile
        val libs = mutableListOf<String>()
        libs += "${javaHome}/jmods/java.base.jmod(!**.jar;!module-info.class)"
        for (m in listOf("java.sql", "java.logging", "java.naming", "java.management", "java.xml",
                "java.desktop", "java.transaction.xa", "java.sql.rowset")) {
            val jmod = file("${javaHome}/jmods/$m.jmod")
            if (jmod.exists()) libs += "${jmod}(!**.jar;!module-info.class)"
        }
        for (f in configurations.compileClasspath.get().files) libs += f.absolutePath
        args(
            "-injars", inJar.absolutePath,
            "-outjars", outTmp.absolutePath)
        libs.forEach { args("-libraryjars", it) }
        args("@${rules.absolutePath}")
    }
    doLast {
        Files.copy(outTmp.toPath(), outAzs.toPath())
    }
}

// 3) The plain (readable) packaging, used with -PnoObfuscate for debugging and tests.
val plainAzs = tasks.register<Jar>("plainAzs") {
    archiveFileName = "$systemName.azs"
    from(sourceSets.main.get().output)
    from(configurations["bundled"].map { if (it.isDirectory) it else zipTree(it) })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

val azs = tasks.register("azs") {
    group = "build"
    description = "Builds build/libs/$systemName.azs (obfuscated unless -PnoObfuscate)"
    if (noObfuscate) dependsOn(plainAzs) else dependsOn(obfuscatedAzs)
}

tasks.named("build") { dependsOn(azs) }
