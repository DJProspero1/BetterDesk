plugins {
    kotlin("jvm")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
    id("org.jetbrains.compose")
}
kotlin { jvmToolchain(17) }
sourceSets.main { kotlin.srcDir("../shared/src/main/kotlin") }
dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.80")
    implementation("net.java.dev.jna:jna-platform:5.16.0")
    implementation("org.bytedeco:ffmpeg:8.1.2-1.5.14")
    runtimeOnly("org.bytedeco:ffmpeg:8.1.2-1.5.14:windows-x86_64")
    runtimeOnly("org.bytedeco:javacpp:1.5.14:windows-x86_64")
    testImplementation(kotlin("test-junit"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation(compose.desktop.uiTestJUnit4)
}
compose.desktop {
    application {
        mainClass = "app.betterdesk.desktop.MainKt"
        nativeDistributions {
            packageName = "BetterDesk"
            packageVersion = "0.6.0"
            description = "Simple attended help for Windows and Android"
            vendor = "BetterDesk contributors"
            modules("java.desktop", "java.prefs", "java.naming", "java.logging", "jdk.crypto.ec", "jdk.unsupported")
            windows { iconFile.set(project.file("../assets/betterdesk.ico")) }
        }
    }
}
