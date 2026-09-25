// Standalone TVBGoneAndroid project.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val ciBuildNumber = providers.environmentVariable("GITHUB_RUN_NUMBER")
    .orNull
    ?.toIntOrNull()
    ?: 1

android {
    namespace = "com.gokuencinar.iruniversal"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gokuencinar.iruniversal"
        minSdk = 30
        targetSdk = 35
        versionCode = ciBuildNumber
        versionName = "0.1.0-android"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
}
