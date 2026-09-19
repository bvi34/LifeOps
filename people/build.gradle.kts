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
    //
    // The partner seam (`partner/`) pairs this household with somebody else's install. It needs
    // LifeOps' week — to publish it, and to put a partner's contributed task on it — but it must not
    // *depend* on LifeOps: `:lifeops` already depends on this module, and a second edge back would
    // be a cycle. So People declares the port (`partner/HouseholdWeek`) and LifeOps registers the
    // adapter for it at startup, which is the same direction every other call between them runs.
    // What crosses is bounded by that port: a partner's week is a view in People, never rows in a
    // planner.
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.people.app"
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
    // The suite's shelf. A person is one of the two things in the household that most obviously has
    // paperwork about it — the passport, the birth certificate, the immunisation record — and this
    // is one dependency and one composable rather than a second document store in this app.
    implementation(project(":repository"))

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
    // Partner pairing by QR code. The encoder (`partner/QrMatrix`) is plain Java and unit-tested
    // against zxing's own decoder; the camera half brings the scanning activity and declares the
    // CAMERA permission the manifest merger folds into the host.
    implementation(libs.zxing.core)
    implementation(libs.zxing.android.embedded)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
