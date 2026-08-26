plugins {
    // Citation is now a *library* consumed by the Operations Sandbox container app (:app).
    // It keeps its package, reader, and ingestion; it just no longer owns a launcher or Application.
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Citation — the reading surface that ingests content, lets you read it, and pulls notes from it
// while preserving each note's source. It is hosted inside the Operations Sandbox container app
// (:app) alongside LifeOps; the two still talk only over the sync seam in :core. Everything
// framework-independent (content model, keys, dedup, EPUB parse, anchors, notes, sync) lives in
// :core and is JVM-tested; this module adds Room storage, the Compose reader, and WorkManager
// ingestion jobs on top.
android {
    namespace = "com.citation.app"
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
    implementation(project(":core"))
    // The Operations Sandbox backup format/engine (pure JVM). Citation supplies a BackupContributor.
    implementation(project(":backupkit"))

    // PDF text extraction for the reflow track (see data/pdf/PdfPageText). The same PDFBox-Android
    // port :logistics already uses for the Walmart order import, so the app carries it either way.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

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
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.gson)
    // Encrypted-at-rest storage (Android Keystore) for the library card + PIN.
    implementation(libs.androidx.security.crypto)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
