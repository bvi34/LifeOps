plugins {
    // LifeOps is now a *library* consumed by the Operations Sandbox container app (:app),
    // not an installable application of its own. It keeps its package/namespace and every screen;
    // it just no longer owns the launcher, applicationId, or Application class.
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.lifeops.app"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        // Code shrinking is the consuming app's (:app) responsibility, so no
        // applicationId/version/minify here.
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
    // The Operations Sandbox backup format/engine (pure JVM). LifeOps supplies a BackupContributor.
    implementation(project(":backupkit"))
    // Citation's sync spine (pure JVM): the packet/envelope contract + file-drop transport LifeOps
    // reads to ingest reading telemetry and notes. LifeOps is just another peer on the seam.
    implementation(project(":core"))
    // The People directory module. LifeOps depends on it for the *sync contract* — the packet,
    // envelope, binder and merge rule the two peers share — not to read People's database: the
    // household roster is replicated over a mailbox, not borrowed live the way Logistics borrows
    // LifeOps' food catalog. LifeOps keeps owning its own `persons` table and every key into it.
    implementation(project(":people"))
    implementation(libs.androidx.compose.ui.graphics)

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
    implementation(libs.gson)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.glance)
    implementation(libs.androidx.glance.appwidget)
    debugImplementation(libs.androidx.ui.tooling)

    implementation("sh.calvin.reorderable:reorderable-android:2.4.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
