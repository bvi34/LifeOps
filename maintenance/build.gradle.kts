plugins {
    // Maintenance — the suite's asset and upkeep register. Like LifeOps, Citation, Logistics,
    // Advisor, Health, People and Project it is a *library* module consumed by the Operations
    // Sandbox container app (:app); it owns no launcher or Application of its own.
    //
    // Maintenance owns *the things you own*: the house, the cars, the furnace, the mower — what
    // each one is (VIN, serial, parcel number), what is owed on it (the mortgage, the car loan),
    // what it costs to keep (services, premiums), and what it next needs done. What it deliberately
    // does not own is your day: it says a thing is due, never when you will get to it. Scheduling is
    // LifeOps' job, and a second planner would be a second answer to "what am I doing today" — so
    // upkeep is *handed* to that planner (a task dated the day it falls due) and the tick comes back.
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.maintenance.app"
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
    // The Operations Sandbox backup format/engine (pure JVM). Maintenance supplies a
    // BackupContributor.
    implementation(project(":backupkit"))
    // LifeOps, for the week. Maintenance knows *when* a thing is due; LifeOps is where a week is
    // planned, so an upkeep plan publishes itself there as a task dated the day it falls due and
    // takes the tick back (see data/repository/LifeOpsTasks). The dependency points one way only:
    // LifeOps announces completions on a bus and knows nothing about who is listening.
    implementation(project(":lifeops"))

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
    // Parsing two public, keyless government JSON APIs (vPIC and NHTSA recalls). Pure-JVM, so the
    // parsers stay in logic/ and unit-tested — the same split Health uses for its drug lookup.
    implementation(libs.gson)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
