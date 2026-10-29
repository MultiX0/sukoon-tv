plugins {
    id("com.android.application")
}

// A test helper, not part of the launcher: it publishes a silent media session
// with real track metadata so the now playing card and the lyrics can be tested
// without playing any audio on the box.
android {
    namespace = "dev.multix.fakeplayer"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.multix.fakeplayer"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
