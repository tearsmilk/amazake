// AmazakeCore: a standalone, obfuscated .azs system for the Amazake core.
// Built with the root wrapper: ./gradlew -p amazake-core-system build
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "AmazakeCore"
