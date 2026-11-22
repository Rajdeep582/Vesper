// Standalone build for the backend only (no Android SDK needed): ./gradlew -p server-build :server:installDist
//
// Everything resolves from Google's mirror of Maven Central. Maven Central itself answers HTTP 429 to shared build
// machines (Render), and the Gradle Plugin Portal redirects to Central, so neither may be on the path: a 429 from
// any listed repository fails the whole build instead of falling through to the next one.
pluginManagement {
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        maven("https://build-artifacts.signal.org/libraries/maven/") { content { includeGroup("org.signal") } }
    }
    versionCatalogs { create("libs") { from(files("../gradle/libs.versions.toml")) } }
}
rootProject.name = "vesper-server"
include(":shared")
project(":shared").projectDir = file("../shared")
include(":server")
project(":server").projectDir = file("../server")
