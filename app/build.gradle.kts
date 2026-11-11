plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.louay.studiovoice"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.louay.studiovoice"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
        // Redmi 14C is arm64-v8a. Restricting ABIs keeps the APK small:
        // the DeepFilterNet AAR otherwise bundles x86/x86_64 native libs too.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // AI denoise (fast engine): DeepFilterNet (Apache-2.0), model ~8 MB bundled
    // in the AAR, runs 100% on-device at 48 kHz / 16-bit / mono, frame-by-frame.
    implementation("io.github.kaleyravideo:android-deepfilternet:0.0.8")

    // AI denoise (pro engine): ONNX Runtime for the bundled DPDFNet model
    // (assets/dpdfnet2_48khz_hr.onnx, Ceva, Apache-2.0).
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")

    testImplementation("junit:junit:4.13.2")
}

// TEMP PROBE — REMOVE AFTER DIAGNOSIS.
println("::warning title=ProbeAppEnd::app script fully evaluated")

