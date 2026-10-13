plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.hermes.duplex"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.hermes.duplex"
        minSdk = 26
        targetSdk = 34
        versionCode = 79
        versionName = "0.79-poc"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Proof of concept: sign with the debug key so the APK installs
            // by sideload without a keystore ceremony. The watch app must be
            // signed with the same key or the Data Layer will not pair them.
            signingConfig = signingConfigs.getByName("debug")
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

    composeOptions {
        // Pinned to Kotlin 1.9.24. See the version catalog.
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.core.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.okhttp)

    // Material Components is kept for the window theme only: it is what
    // paints the status bar and the first frame before Compose is up.
    implementation(libs.material)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.fragment)
    implementation(libs.lifecycle.runtime.compose)

    // The watch link. Nothing else in the phone app needs Play services.
    implementation(libs.play.services.wearable)
}
