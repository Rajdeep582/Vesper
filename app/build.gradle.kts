import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

// Non-secret client config lives in secrets.properties (git-ignored) at the repo root:
//   vesper.apiUrl.debug=http://localhost:8080        (phone reaches the dev server via `adb reverse tcp:8080 tcp:8080`)
//   vesper.apiUrl.release=https://<service>.onrender.com
val secrets = Properties().apply { rootProject.file("secrets.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) } }
fun cfg(key: String, default: String): String = (secrets.getProperty(key) ?: providers.gradleProperty(key).orNull ?: default).trimEnd('/')

android {
    namespace = "com.rajdeep.vesper"
    compileSdk = 37
    // NDK is only used to strip debug symbols from bundled native libs (libsignal, sqlcipher).
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.rajdeep.vesper"
        minSdk = 29
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Phones only; keeps the APK small (libsignal ships large native libraries per ABI).
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        // Firebase client config (public identifiers, not secrets). Empty => push disabled, app still works.
        buildConfigField("String", "FCM_APP_ID", "\"${cfg("vesper.fcm.appId", "")}\"")
        buildConfigField("String", "FCM_API_KEY", "\"${cfg("vesper.fcm.apiKey", "")}\"")
        buildConfigField("String", "FCM_PROJECT_ID", "\"${cfg("vesper.fcm.projectId", "")}\"")
        buildConfigField("String", "FCM_SENDER_ID", "\"${cfg("vesper.fcm.senderId", "")}\"")
    }

    val ks = rootProject.file("keystore.properties")
    signingConfigs {
        if (ks.exists()) {
            val p = Properties()
            ks.inputStream().use { p.load(it) }
            create("release") {
                storeFile = rootProject.file(p.getProperty("storeFile"))
                storePassword = p.getProperty("storePassword")
                keyAlias = p.getProperty("keyAlias")
                keyPassword = p.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "API_URL", "\"${cfg("vesper.apiUrl.debug", "http://localhost:8080")}\"")
            buildConfigField("boolean", "DEV_TOOLS", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
            buildConfigField("String", "API_URL", "\"${cfg("vesper.apiUrl.release", "https://vesper-api.onrender.com")}\"")
            buildConfigField("boolean", "DEV_TOOLS", "false")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += listOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/INDEX.LIST", "/META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        // Desktop natives bundled inside the libsignal-client JVM jar are useless on Android.
        resources.excludes += listOf("**/*.dylib", "**/*.dll", "/libsignal_jni*.so", "/signal_jni*")
        jniLibs.useLegacyPackaging = false
        // libsignal's test-only native library is never needed at runtime.
        jniLibs.excludes += "**/libsignal_jni_testing.so"
    }
}

kotlin { jvmToolchain(21) }

room { schemaDirectory("$projectDir/schemas") }

dependencies {
    implementation(project(":shared"))
    coreLibraryDesugaring(libs.desugar)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.androidx.exifinterface)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.lifecycle.viewmodel.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.androidx.compiler)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.sqlcipher.android)
    implementation(libs.androidx.sqlite)
    implementation(libs.datastore.preferences)
    implementation(libs.work.runtime)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.libsignal.android)
    implementation(libs.media3.exoplayer)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.kotlinx.coroutines.play.services)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
