plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ai.hb.smartdns"
    compileSdk = 35
    defaultConfig {
        applicationId = "ai.hb.smartdns"
        minSdk = 31
        targetSdk = 35
        versionCode = 4
        versionName = "1.3.0"
    }
}
