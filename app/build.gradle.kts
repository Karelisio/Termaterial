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
        targetSdk = (property("targetSdkVersion") as String).toInt()
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
