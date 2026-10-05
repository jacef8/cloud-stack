plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jacef8.scrollcapture"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jacef8.scrollcapture"
        minSdk = 30
        targetSdk = 34
        // Every CI build gets a higher number so a new APK installs over the old one.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "1.0.$versionCode"
        ndk { abiFilters += "arm64-v8a" }
    }

    // One fixed key (committed on purpose) so every build is signed the same
    // and installs as an update, keeping the accessibility permission.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    // On-device text recognition, bundled so it works offline. Used only when an
    // app does not expose its real text.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.android.gms:play-services-tasks:18.2.0")
    testImplementation("junit:junit:4.13.2")
}
