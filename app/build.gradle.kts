plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

// Firebase push: put google-services.json (Firebase console -> Project settings -> Android app
// "com.icqnew.app") into the app/ folder. Without it the app still builds and works, just without push.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}

android {
    namespace = "com.icqnew.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "icq.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 13
        versionName = "4.0"
        // phones only: ARM (no x86 emulator libraries) — the APK gets much smaller
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    // ONE permanent key: every new version installs over the previous one (and delta updates work)
    signingConfigs {
        create("icq") {
            storeFile = file("icq-new.keystore")
            storePassword = "icqnew-2026-key"
            keyAlias = "icqnew"
            keyPassword = "icqnew-2026-key"
        }
    }

    buildTypes {
        release {
            // R8: removes unused code (most of the icon set etc.), names are kept (-dontobfuscate)
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("icq")
        }
        debug {
            signingConfig = signingConfigs.getByName("icq")
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
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-process:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Room (local cache: contacts + message history)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Ktor: REST + WebSocket
    implementation("io.ktor:ktor-client-core:2.3.12")
    implementation("io.ktor:ktor-client-okhttp:2.3.12")
    implementation("io.ktor:ktor-client-websockets:2.3.12")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Avatars, images, emoji pictures
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // Audio / video calls
    implementation("io.getstream:stream-webrtc-android:1.1.3")
    // QR scanner for logging in to the web version
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    // CameraX: round video messages recorded inside the app (flip camera, flash)
    implementation("androidx.camera:camera-camera2:1.3.4")
    implementation("androidx.camera:camera-lifecycle:1.3.4")
    implementation("androidx.camera:camera-video:1.3.4")
    implementation("androidx.camera:camera-view:1.3.4")

    // Push notifications
    implementation(platform("com.google.firebase:firebase-bom:33.5.1"))
    implementation("com.google.firebase:firebase-messaging")
}
