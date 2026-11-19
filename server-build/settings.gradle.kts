// Standalone build for the backend only (no Android SDK needed): ./gradlew -p server-build :server:installDist
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://build-artifacts.signal.org/libraries/maven/") { content { includeGroup("org.signal") } }
    }
    versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }
}
rootProject.name = "vesper-server"
include(":shared")
project(":shared").projectDir = file("../shared")
include(":server")
project(":server").projectDir = file("../server")
