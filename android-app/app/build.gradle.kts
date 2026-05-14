plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.chuest.romtools"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.chuest.romtools"
        minSdk = 29           // 10+, native exec from nativeLibraryDir only
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    packaging {
        // jniLibs that are actually executables (not real .so) must be uncompressed and extracted on install.
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // dexlib2: direct dex byte-code patching, replaces the apktool d/b round-trip for jars whose
    // patches only touch dex (services.jar, miui-services.jar, most miui apk patches).
    implementation("com.android.tools.smali:smali-dexlib2:3.0.5")
    // smali assembler: parse pre-built .smali fragments / classes shipped under assets
    // (files/app/Settings/com/**, basicInfoReplace.smali) into dexlib2 ClassDef / methods so the
    // Settings.apk patch can be done without APKEditor.
    implementation("com.android.tools.smali:smali:3.0.5")
    // ARSCLib: binary resources & AXML read/write — needed for the Settings.apk resource patches
    // (add a new layout entry, register public id, modify string-array values).
    implementation("io.github.reandroid:ARSCLib:1.3.8")
}
