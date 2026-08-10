plugins {
    // Advisor — the suite's private, on-device assistant. Like LifeOps, Citation and Logistics it is
    // a *library* module consumed by the Operations Sandbox container app (:app); it owns no launcher
    // or Application of its own. It is the suite's RAG layer: it reads the other apps' own data (in
    // the same process, gated by per-app permissions the user grants) and answers questions grounded
    // in that data. The language model itself is a **placeholder** today — a small local model
    // (~2–4B params, GGUF/Q4) is the intended drop-in. Everything runs offline; there is no network.
    //
    // Its retrieval/permission/prompt logic lives, framework-free, under `logic/` and is JVM
    // unit-tested (no emulator), the same discipline as :backupkit and Citation's :core.
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.advisor.app"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
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

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // The other suite apps: Advisor's knowledge sources read their databases (same process) to build
    // the retrieval corpus. It never writes to them — it is a read-only consumer of their data.
    implementation(project(":lifeops"))
    implementation(project(":citation"))
    implementation(project(":logistics"))
    // The Operations Sandbox backup format/engine (pure JVM). Advisor supplies a BackupContributor
    // for its own store (granted permissions + saved conversations).
    implementation(project(":backupkit"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
