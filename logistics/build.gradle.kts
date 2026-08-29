plugins {
    // Logistics — the suite's pantry/inventory app. Like LifeOps and Citation it is a *library*
    // module consumed by the Operations Sandbox container app (:app); it owns no launcher or
    // Application of its own. It depends on :lifeops so it can reuse LifeOps' food-item and recipe
    // catalog as the source of truth, and adds only what's new: pantry stock, import provenance,
    // and the meal-consumption ledger. Its own pure-JVM parsers (Walmart order text, recipe links)
    // live under `logic/` and are unit-tested on the JVM.
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.logistics.app"
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
    // Reuse LifeOps' food/recipe catalog + repositories (same process). Logistics is a peer app but
    // sources its foods and recipes from LifeOps, so it depends on the module directly.
    implementation(project(":lifeops"))
    // The Operations Sandbox backup format/engine (pure JVM). Logistics supplies a BackupContributor.
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
    implementation(libs.gson)
    // On-device PDF text extraction for the Walmart order import. Pure-JVM PDFBox port; the parser
    // that turns the extracted text into pantry lines (logic/WalmartOrderParser) is framework-free.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // On-device OCR for the recipe-screenshot import. The *bundled* Latin recogniser: the model
    // ships inside the app, so reading a screenshot needs no network, no Play Services download and
    // no account — the only shape of this that belongs in an offline-first suite. The layout reading
    // it feeds (logic/RecipeTextParser) is framework-free and unit-tested.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
