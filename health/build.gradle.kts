plugins {
    // Health — the suite's household health tracker. Like LifeOps, Citation and Logistics it is a
    // *library* module consumed by the Operations Sandbox container app (:app); it owns no launcher
    // or Application of its own.
    //
    // Unlike Logistics it depends on no other app module: nobody else owns people, temperatures or
    // doses, so Health owns them outright and shares them the two ways the suite already shares
    // things — a BackupContributor (:backupkit) and a read-only Advisor knowledge source.
    //
    // The judgement calls — what counts as a fever at this age and site, whether the next dose is
    // due yet, how an illness is going — live in `logic/` as framework-free Kotlin and are
    // JVM-unit-tested. Nothing in there imports Android.
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.health.app"
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
    // The Operations Sandbox backup format/engine (pure JVM). Health supplies a BackupContributor.
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
