// Standalone build for the backend only (no Android SDK needed): ./gradlew -p server-build :server:installDist
// Maven Central rate-limits shared build IPs (HTTP 429 on Render's builders), so resolve through the Gradle
// Plugin Portal and Google's Maven Central mirror first and keep Central itself as the last fallback.
pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
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
