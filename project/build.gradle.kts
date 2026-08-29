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
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // The suite's shared appearance — one theme, one store, this app's colour identity in it.
    implementation(project(":suiteui"))
    // The Operations Sandbox backup format/engine (pure JVM). Project supplies a BackupContributor.
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
