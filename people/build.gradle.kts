plugins {
    // People — the suite's household directory. Like LifeOps, Citation, Logistics and Health it is a
    // *library* module consumed by the Operations Sandbox container app (:app); it owns no launcher
    // or Application of its own.
    //
    // People owns *identity*: who is in the household, how to reach them, the dates that matter, and
    // the running notes about them. It does not own what other apps do with those people — LifeOps
    // keeps its own task involvement and weather tolerances, Health keeps its readings — and it
    // reaches them over the sync seam rather than by reading their databases. That is the difference
    // between this module and Logistics' `LifeOpsCatalog`: a catalog is read live from its owner in
    // the same process; a directory is *replicated*, because both ends can edit it.
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.people.app"
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
    // The suite's shared appearance — one theme, one store, this app's colour identity in it.
    implementation(project(":suiteui"))
    // The suite's sync spine. `Mailbox` — the monotonic-version bookkeeping every peer syncs over —
    // lives in :core alongside Citation's use of it; People rides the same seam rather than growing
    // a second implementation of the same protocol.
    implementation(project(":core"))
    // The Operations Sandbox backup format/engine (pure JVM). People supplies a BackupContributor.
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
    // The sync wire format (see sync/PeopleSyncCodec) — pure JVM, same codec choice as :core.
    implementation(libs.gson)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
