plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing comes from CI through the environment (KEYSTORE_FILE,
// KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD); without it, release builds fall
// back to the debug key so anyone can build. VERSION_CODE / VERSION_NAME
// likewise come from the CI tag when set.
val keystoreFile = System.getenv("KEYSTORE_FILE")
val ciVersionCode = System.getenv("VERSION_CODE")?.toIntOrNull()
val ciVersionName = System.getenv("VERSION_NAME")

android {
    namespace = "com.duoopen"
    compileSdk = 35

    defaultConfig {
        // Own app id so this build installs beside (not over) the upstream one.
        applicationId = "com.eberhard.duoopen"
        // AGSL RuntimeShader needs API 33.
        minSdk = 33
        targetSdk = 35
        versionCode = ciVersionCode ?: 4
        versionName = ciVersionName ?: "1.3.0"
    }

    // full: system-wide fold via the accessibility service (+ wallpaper).
    // lite: live wallpaper only — no accessibility service in the manifest,
    // so Play Protect / restricted settings never get involved. Separate app
    // id so both can be installed side by side.
    flavorDimensions += "edition"
    productFlavors {
        create("full") {
            dimension = "edition"
            isDefault = true
        }
        create("lite") {
            dimension = "edition"
            applicationIdSuffix = ".lite"
            versionNameSuffix = "-lite"
        }
    }

    signingConfigs {
        if (keystoreFile != null) {
            create("release") {
                storeFile = file(keystoreFile)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.06.01"))
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.window:window:1.2.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
