import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

// API base URL is injected at build time so the real domain never lands in the
// (public) repo. Add a line to wear/local.properties (gitignored):
//     capsule.api.url=https://your-worker.workers.dev
// Falls back to the placeholder when unset (e.g. CI / fresh clone).
val capsuleApiUrl: String = run {
    val props = Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    props.getProperty("capsule.api.url")
        ?: (project.findProperty("capsule.api.url") as String?)
        ?: "https://<your-subdomain>.workers.dev"
}

android {
    namespace = "com.lightningcapsule.wear"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.lightningcapsule.wear"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "CAPSULE_API_URL", "\"$capsuleApiUrl\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // AGP 9.x built-in Kotlin: configure the Kotlin compiler here.
    kotlin {
        jvmToolchain(17)
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.wear.compose.foundation)
    implementation(libs.androidx.wear.compose.material3)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)

    // v3 — watch face complication (quick entry from the watch face).
    implementation(libs.androidx.wear.complications.data.source.ktx)

    // v3 — Wear OS 7 Widget (swipe-left card), built with Jetpack Glance for
    // Wear + Remote Compose. Alpha libraries: this is the only supported widget
    // surface on Wear OS 7 (Tiles is sunset), so it is not "optional".
    implementation(libs.androidx.glance.wear)
    implementation(libs.androidx.glance.wear.core)
    implementation(libs.androidx.compose.remote.creation.compose)
    implementation(libs.androidx.compose.remote.core)
    implementation(libs.androidx.wear.compose.remote.material3)

    debugImplementation(libs.androidx.compose.ui.tooling)

    // Queue index is plain JSON; use the real org.json impl on the unit-test
    // classpath so it isn't the empty android.jar stub.
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
