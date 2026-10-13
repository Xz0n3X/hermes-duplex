plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

/*
 * What the phone and the watch both need, and nothing either of them can
 * only do.
 *
 * No Activities, no views, no Compose. The test of whether something belongs
 * here is whether it would still make sense with no screen attached: the
 * conversation, the sidebar's state, the turn timings, the orb's maths, the
 * contract the two apps talk over.
 *
 * minSdk stays at the phone's 26 even though the watch needs 30 -- a library
 * has to reach down to its lowest consumer, and the watch reaching up costs
 * nothing.
 */
android {
    namespace = "org.hermes.duplex.core"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
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
    implementation(libs.core.ktx)
    implementation(libs.coroutines.android)
    implementation(libs.okhttp)
}
