plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// :sandbox — the Operations Sandbox container. This is the single installable application; it hosts
// LifeOps (:app) and Citation (:citation) as library modules in one process with shared storage,
// which is what makes a true cross-app "back up everything into one zip / restore from it" possible
// without any inter-process plumbing. The home screen picks an app to open and drives backup/restore.
android {
    namespace = "com.operations.sandbox"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.operations.sandbox"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            // Code shrinking is off for now: the merged LifeOps+Citation code needs a vetted
            // keep-rule set (Room/Gson/Glance/WorkManager reflection) before minify can be trusted.
            // Left as a deliberate follow-up so the first container build is verifiable end-to-end.
            isMinifyEnabled = false
        }
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

dependencies {
    // The two hosted apps (now libraries) and the shared backup engine.
    implementation(project(":app"))
    implementation(project(":citation"))
    implementation(project(":backupkit"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation("junit:junit:4.13.2")
}
