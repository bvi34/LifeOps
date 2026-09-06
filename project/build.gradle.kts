plugins {
    // Project — the suite's document and planning repository. Like LifeOps, Citation, Logistics,
    // Health and People it is a *library* module consumed by the Operations Sandbox container app
    // (:app); it owns no launcher or Application of its own.
    //
    // Project owns *the work you are making*: the outline it is shaped by, the documents it is
    // written in, the lore it is consistent with, the timeline it happens on, and the board it gets
    // built through. Those five are not five apps — they are five views of one project, which is why
    // they share a database and a workspace rather than living in separate modules. What Project
    // deliberately does not own is *when you will do it*: scheduling a day's work is LifeOps' job,
    // and a second planner would be a second answer to "what am I doing today".
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

/**
 * Where Room's exported schemas live: written by KSP, read back by `ProjectMigrationTest`, which
 * builds a database from each of them and opens it through the production builder. Named once so the
 * writer and the reader cannot drift apart.
 */
val schemaRoot = "$projectDir/schemas"

android {
    namespace = "com.project.app"
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

    testOptions {
        // Robolectric needs the merged Android resources to stand a context up. The store is the one
        // part of this module that cannot be tested by reasoning about it — what the repository and
        // the schema tests ask about (that a cascade really cascades, that a soft link is cut rather
        // than followed, that a database written at version 1 still opens) is SQLite's behaviour and
        // not Kotlin's — so `gradle :project:testDebugUnitTest` covers it on the JVM, without a device.
        unitTests.isIncludeAndroidResources = true

        // The schemas Room exports, handed to the tests that read them. `ProjectMigrationTest` builds
        // a database from each one and opens it through the production builder, so the DDL it tests
        // is the DDL that shipped rather than a copy of it that could drift.
        unitTests.all {
            it.systemProperty("project.schemaDir", "$schemaRoot/com.project.app.data.db.ProjectDatabase")
        }
    }
}

ksp {
    arg("room.schemaLocation", schemaRoot)
}

dependencies {
    // The suite's shared appearance and its shared controls — one theme, one store, this app's
    // colour identity in it, and the one text, note and number field every app uses. No app
    // grows its own again; see docs/PROJECT.md.
    implementation(project(":suiteui"))
    // The Operations Sandbox backup format/engine (pure JVM). Project supplies a BackupContributor.
    implementation(project(":backupkit"))
    // The suite's shelf. A project's *files* — the brief, the contract, the reference PDFs somebody
    // was sent — are documents the household filed, not writing the project is made of, and they
    // belong in the one place the suite keeps documents. The arrow points into Repository and never
    // back: it has no idea what a project is, and is told the name each time the panel is shown.
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
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // The store, on the JVM. Everything above the repository is pure logic and needs nothing to test
    // it; the database is the exception, because the guarantees it makes are SQLite's. The same pair
    // Maintenance uses, at the same versions.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}
