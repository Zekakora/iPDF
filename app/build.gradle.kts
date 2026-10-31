plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.doge.ipdf"
    compileSdk = 36

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    defaultConfig {
        applicationId = "com.doge.ipdf"
        minSdk = 29
        targetSdk = 36
        versionCode = 14
        versionName = "0.5.2"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    buildTypes {
        debug {
            isDebuggable = true
        }
        release {
            isDebuggable = false
            // No production keystore is checked into this repository. The existing release
            // artifacts are signed with the standard debug certificate, so reuse that already
            // configured signer here; this keeps local release builds installable without
            // inventing or exposing credentials. Replace this with a real release signer before
            // publishing.
            signingConfig = signingConfigs.getByName("debug")
            // R8 is left off deliberately: PDFBox and the optional reflective PenEngine bridge are
            // not device-validated under shrinking yet. The release variant still excludes debug
            // tooling and compiles out verbose input diagnostics via BuildConfig.DEBUG.
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    // Xiaomi PenEngine SDK (optional): drop PenEngine-release_*.aar into app/libs.
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))
    implementation(platform("androidx.compose:compose-bom:2025.10.00"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    // Parses and writes standard PDF annotations (PDAnnotationInk-style /Ink dictionaries).
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
