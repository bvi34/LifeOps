plugins {
    // Repository — the suite's shelf. Like every other hosted app it is a *library* module consumed
    // by the Operations Sandbox container (:app); it owns no launcher and no Application of its own.
    //
    // What it owns is the paperwork: the mortgage statement, the manual, the warranty, the title.
    // Two doors onto the same documents. Its own screen is one — everything the household has filed,
    // searchable, without knowing which app it came in through — and the other is the section it
    // lends to the app that owns the thing, so a manual sits on the asset it belongs to.
    //
    // The dependency arrow points *into* this module and never out: Repository knows nothing about
    // assets, people or projects. An app that wants documents depends on this one; an app that
    // already stores its own lends them to the shelf through `source/DocumentSource`, which is a
    // read-only seam and not a migration.
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.repository.app"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
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
        unitTests.isIncludeAndroidResources = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":suiteui"))
    implementation(project(":backupkit"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
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
