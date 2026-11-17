plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "eu.bmesoft.insvmagik"
    compileSdk = 36

    defaultConfig {
        applicationId = "eu.bmesoft.insvmagik"
        minSdk = 29
        targetSdk = 36
        // tools/install.ps1 sets BUILD_NUMBER (minutes since 2026-01-01), so every build installs
        // as an update over the previous one.
        val build = System.getenv("BUILD_NUMBER")?.toIntOrNull() ?: 1
        versionCode = build
        versionName = "0.0.$build"
    }

    buildTypes {
        // Internal tool: only debug builds (default debug key), no R8.
        debug { }
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
