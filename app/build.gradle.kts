plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.litestore"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.litestore"
        minSdk = 21          // runs on old Android TV boxes (5.0+)
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        resConfigs("en")
    }
    buildTypes {
        release {
            isMinifyEnabled = true      // smaller APK
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
}

dependencies {
    // Only dependency: QR generation (~500 KB). No AppCompat, no Compose, no image libs.
    implementation("com.google.zxing:core:3.5.3")
}
