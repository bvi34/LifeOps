plugins {
    // Health — the suite's household health tracker. Like LifeOps, Citation and Logistics it is a
    // *library* module consumed by the Operations Sandbox container app (:app); it owns no launcher
    // or Application of its own.
    //
    // Health owns temperatures, doses and illnesses outright. It does *not* own the people they are
    // recorded against — People does — so it joins the People sync seam as a bind-only peer and
    // shares its own data the two ways the suite already shares things: a BackupContributor
    // (:backupkit) and a read-only Advisor knowledge source.
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
    // The suite's shared appearance — one theme, one store, this app's colour identity in it.
    implementation(project(":suiteui"))
    // The Operations Sandbox backup format/engine (pure JVM). Health supplies a BackupContributor.
    implementation(project(":backupkit"))
    // The People directory module — for the *sync contract* (packet, binder, merge, engine), not to
    // read People's database. Health is a bind-only peer on that seam: it keeps the people it
    // already tracks in step and never grows a profile for one it doesn't. See docs/PEOPLE.md.
    implementation(project(":people"))
    // Repository, for the suite's shelf. Health keeps its own documents — it kept them first, and
    // moving them would be a migration of the most sensitive rows in the suite for a tidier diagram.
    // What it does instead is *lend* them: `shelf/HealthDocumentSource` is read-only, so a lab result
    // is findable beside the mortgage statement while everything that changes one still happens here.
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
    // Parsing the two drug references (RxNorm, openFDA). Already a suite dependency — LifeOps
    // parses the weather API with it — so this adds a module edge, not a library.
    implementation(libs.gson)
    // Medication reminders. WorkManager, not AlarmManager: a dose reminder is a "some time around
    // eight" nudge that must survive a reboot, not a to-the-second alarm, and the sandbox host
    // already carries the dependency for LifeOps' own reminders.
    implementation(libs.androidx.work.runtime.ktx)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
