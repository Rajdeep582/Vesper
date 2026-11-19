plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin { jvmToolchain(21) }

application { mainClass.set("com.rajdeep.vesper.server.MainKt") }

dependencies {
    implementation(project(":shared"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.forwarded.header)
    implementation(libs.ktor.server.compression)
    implementation(libs.ktor.serialization.json)
    implementation(libs.mongodb.kotlin.coroutine)
    implementation(libs.password4j)
    implementation(libs.tink.jvm)
    implementation(libs.cloudinary)
    implementation(libs.logback)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(libs.ktor.client.websockets)
    testImplementation(libs.mongo.java.server)
    testImplementation(libs.libsignal.client)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// Utility tasks: ./gradlew :server:hashPassword --args="your-admin-password"   and   ./gradlew :server:genKeyset
tasks.register<JavaExec>("hashPassword") {
    group = "vesper"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.rajdeep.vesper.server.ToolsKt")
    args("hash")
}
tasks.register<JavaExec>("genKeyset") {
    group = "vesper"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.rajdeep.vesper.server.ToolsKt")
    args("keyset")
}
tasks.register<JavaExec>("genSecret") {
    group = "vesper"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.rajdeep.vesper.server.ToolsKt")
    args("secret")
}

// Second test client for on-device testing (real libsignal + protocol):
//   ./gradlew -p server-build :server:sim --args="http://localhost:8080 testbot <password> listen 60 echo"
tasks.register<JavaExec>("sim") {
    group = "vesper"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.rajdeep.vesper.server.SimClientKt")
}
