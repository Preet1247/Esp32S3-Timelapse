// Resource-only Watch Face Format watch face (no code, no Kotlin plugin).
// Package name follows the Watch Face Push rule: <marketplace package>.watchfacepush.<name>
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.watchlink.companion.watchfacepush.classic"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.watchlink.companion.watchfacepush.classic"
        minSdk = 33
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("watchlink") {
            storeFile = rootProject.file("keystore/watchlink-dev.jks")
            storePassword = "watchlink"
            keyAlias = "watchlink"
            keyPassword = "watchlink"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("watchlink")
        }
        debug {
            signingConfig = signingConfigs.getByName("watchlink")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}
