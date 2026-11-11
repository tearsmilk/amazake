// Standalone build: the root Amazake (Paper fork) build does not include this directory.
// Build with the root wrapper: ./gradlew -p stress-plugin build
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "AmazakeStress"
