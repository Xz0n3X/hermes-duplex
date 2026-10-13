plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/*
 * The watch app.
 *
 * applicationId matches the phone's on purpose, and both are signed with the
 * same key. The Data Layer pairs apps by package identity and signature; a
 * mismatch in either does not error, it just means the watch never discovers
 * a node and nothing ever arrives.
 *
 * minSdk 30 is Wear OS 3. Below that is a different UI toolkit and a
 * different story about standalone apps, and this one is not standalone: it
 * needs the phone to reach the bridge.
 */
android {
    namespace = "org.hermes.duplex.wear"
    compileSdk = 34

    defaultConfig {
        applicationId = "org.hermes.duplex"
        minSdk = 30
        targetSdk = 34
        versionCode = 60
        versionName = "0.60-poc"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // The same debug key as the phone app, so the pair is signed
            // identically and the Data Layer will talk between them.
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
        // Off by default in AGP 8; the watch reports its own version into
        // the shared EventLog, which a library cannot do for it.
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
    implementation(libs.play.services.wearable)
    implementation(libs.okhttp)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play.services)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.fragment)
    implementation(libs.lifecycle.runtime.compose)

    implementation(libs.wear.compose.material)
    implementation(libs.wear.compose.foundation)
    implementation(libs.wear.compose.navigation)
}
