plugins {
    // :suiteui — the Android half of the suite's appearance: the store that persists what the
    // Operations Sandbox settings choose, and the one Compose theme every hosted app wraps itself
    // in. It holds no colour decisions of its own; :suitekit resolves the scheme on the JVM and
    // this module only adapts it to Material 3 and to a Material icon per app.
    //
    // Every hosted app depends on this module, and none of them depends on :app — the container
    // *edits* appearance, but the apps read it straight from the store, so opening an app from
    // anywhere still lands on the suite's look.
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.operations.suite.ui"
    compileSdk = 36

    defaultConfig {
        minSdk = 34
        // Code shrinking is the consuming app's (:app) responsibility.
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // The appearance contract itself (pure JVM). `api` because every consumer of SuiteTheme speaks
    // in its types — SuiteAppearance, SuitePreset, SuiteApps — and AppId is how apps are addressed.
    api(project(":suitekit"))
    api(project(":backupkit"))

    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation("junit:junit:4.13.2")
}
