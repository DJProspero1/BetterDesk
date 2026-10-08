plugins { id("com.android.application"); kotlin("android") }
android {
    namespace = "app.betterdesk.motionfixture"
    compileSdk = 36
    defaultConfig { applicationId = "app.betterdesk.motionfixture"; minSdk = 26; targetSdk = 36; versionCode = 1; versionName = "1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
kotlin { jvmToolchain(17) }
