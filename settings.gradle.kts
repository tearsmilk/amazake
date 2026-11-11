import java.util.Locale

pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

if (!file(".git").exists()) {
    error(
        """
        Amazake must be built from a git clone (paperweight applies its patches with git),
        not from a source zip: git clone https://github.com/tearsmilk/amazake
        """.trimIndent()
    )
}

rootProject.name = "amazake"

for (name in listOf("amazake-api", "amazake-server")) {
    include(name.lowercase(Locale.ENGLISH))
}
