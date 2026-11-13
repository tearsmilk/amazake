// Сборка системы Amazake: обычный jar с расширением .azs.
//   ./gradlew azs                      -> build/libs/<Имя>.azs
//   ./gradlew azs -PamazakeApiJar=путь -> собрать против своего jar API (иначе он скачивается из последнего релиза)
//   ./gradlew refreshSystemsApi        -> скачать API заново (после выхода нового релиза ядра)
plugins {
    java
}

// Имя системы = имя проекта (settings.gradle.kts) = name в system.yml = имя файла .azs.
val systemName = rootProject.name
group = "core.amazake.systems"
version = "1.0.0"

val apiUrl = "https://github.com/tearsmilk/amazake/releases/latest/download/amazake-systems-api.jar"
val apiJar: File = (findProperty("amazakeApiJar") as String?)?.let { file(it) }
    ?: layout.projectDirectory.file("libs/amazake-systems-api.jar").asFile.also { jar ->
        if (!jar.exists()) {
            jar.parentFile.mkdirs()
            logger.lifecycle("Скачиваю API систем Amazake: $apiUrl")
            val tmp = File(jar.parentFile, jar.name + ".part")
            uri(apiUrl).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            tmp.renameTo(jar)
        }
    }

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // И Paper API, и API систем есть на сервере: в .azs они не кладутся (compileOnly).
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    compileOnly(files(apiJar))

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
    val props = mapOf("name" to systemName, "version" to project.version.toString())
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("system.yml") {
        expand(props)
    }
}

tasks.jar {
    archiveFileName.set("$systemName.azs")
}

tasks.register("azs") {
    group = "build"
    description = "Собирает build/libs/<Имя>.azs"
    dependsOn(tasks.jar)
}

tasks.register<Delete>("refreshSystemsApi") {
    group = "build setup"
    description = "Удаляет скачанный jar API систем: при следующей сборке скачается свежий"
    delete("libs/amazake-systems-api.jar")
}

tasks.test {
    useJUnitPlatform()
}

// Тесты чистой логики (без сервера): Paper API нужен и им.
dependencies {
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
}
