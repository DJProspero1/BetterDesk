plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("plugin.compose")
    kotlin("plugin.serialization")
    id("org.jetbrains.compose")
}
android {
    namespace = "app.betterdesk"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.betterdesk"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "0.7.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets["main"].java.srcDir("../shared/src/main/kotlin")
    if (providers.gradleProperty("betterdesk.rtcProof").map(String::toBoolean).getOrElse(false)) {
        sourceSets["main"].java.srcDirs("../experimental/shared", "../experimental/android")
        sourceSets["androidTest"].java.srcDir("../experimental/androidTest")
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    buildFeatures { compose = true }
    packaging { resources.excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*") }
}
kotlin { jvmToolchain(17) }
dependencies {
    if (providers.gradleProperty("betterdesk.rtcProof").map(String::toBoolean).getOrElse(false)) {
        implementation("io.github.webrtc-sdk:android:150.7871.01")
    }
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.80")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
