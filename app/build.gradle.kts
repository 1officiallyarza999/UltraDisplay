plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.ultradisplay.app"
    compileSdk = 35
    defaultConfig { applicationId = "com.ultradisplay.app"; minSdk = 26; targetSdk = 35; versionCode = 3; versionName = "0.2.1" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
