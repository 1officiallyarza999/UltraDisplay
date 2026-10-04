plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.ultradisplay.app"
    compileSdk = 35
    defaultConfig { applicationId = "com.ultradisplay.app"; minSdk = 26; targetSdk = 35; versionCode = 12; versionName = "0.6.0" }
    buildFeatures { aidl = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    // Shizuku: optional elevated (adb-level) access for real touch injection and tablet-sized virtual displays.
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}

android.lint {
    abortOnError = false
    textReport = true
    textOutput = file("build/reports/lint-results-debug.txt")
    checkDependencies = false
}
