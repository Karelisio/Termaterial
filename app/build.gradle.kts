plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.termaterial.app"
    compileSdk = (property("compileSdkVersion") as String).toInt()

    defaultConfig {
        applicationId = "io.termaterial.app"
        minSdk = (property("minSdkVersion") as String).toInt()
        // Deliberately NOT the shared gradle.properties targetSdkVersion (35): the bootstrap's
        // bash is downloaded and extracted into the app's private data directory at runtime and
        // exec'd directly (see docs/step-2-shell-backend.md). Android's W^X exec-from-app-data-dir
        // restriction only applies to apps *targeting* API 29+ - it is gated on the app's own
        // targetSdkVersion, not the device's Android version - so an app targeting < 29 keeps the
        // legacy behavior (allowed to exec files it wrote) even when running on Android 10+.
        // Confirmed on a real device: exec("...bash"): Permission denied at targetSdk 35, which
        // this works around. Not eligible for Play Store (which requires a much newer target),
        // irrelevant for a sideloaded app. See docs/step-2-shell-backend.md for the alternative
        // (vendor bash/proot into jniLibs at build time) if a higher targetSdk becomes necessary.
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        getByName("debug") {
            // A stable, checked-in debug key (debug keys are meant to be shared, unlike release
            // keys) so every CI build is signed identically and installs as an update over the
            // previous one. Without this, AGP falls back to auto-generating ~/.android/debug.keystore
            // on whichever machine builds it - a fresh, different key on every ephemeral CI runner,
            // forcing an uninstall before every single update.
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":terminal-emulator"))
    implementation(project(":terminal-view"))
    implementation(project(":shell"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
}
