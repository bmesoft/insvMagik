plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The release key (the APK people download) never sits in the repo: the release workflow writes
// it from the repo's secrets and points SIGNING_KEYSTORE_FILE at it. Without it a release build is
// signed with the debug key, so it can still be built and tried anywhere.
val releaseKeystore: String? = System.getenv("SIGNING_KEYSTORE_FILE")

android {
    namespace = "eu.bmesoft.insvmagik"
    compileSdk = 36

    defaultConfig {
        applicationId = "eu.bmesoft.insvmagik"
        minSdk = 29
        targetSdk = 36
        // tools/install.ps1 and the workflows set BUILD_NUMBER (minutes since 2026-01-01), so every
        // build installs as an update over the previous one; a release sets VERSION_NAME too.
        val build = System.getenv("BUILD_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = System.getenv("VERSION_NAME") ?: "0.0.$build"
    }

    signingConfigs {
        releaseKeystore?.let { path ->
            create("release") {
                storeFile = file(path)
                storePassword = System.getenv("SIGNING_PASSWORD")
                keyAlias = "insvmagik"
                keyPassword = System.getenv("SIGNING_PASSWORD")
            }
        }
    }

    buildTypes {
        // Local and pull-request builds: the machine's debug key.
        debug { }
        // The downloadable APK (.github/workflows/release.yml). No R8: the app is small, and its
        // stack traces stay readable in reports.
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        // The sample-frame tests hold two 8K-class lens pictures in memory.
        unitTests.all { it.maxHeapSize = "3g" }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
}
