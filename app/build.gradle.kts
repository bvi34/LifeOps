plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// :app — the Operations Sandbox container. This is the single installable application (and the
// default Android Studio run target); it hosts LifeOps (:lifeops), Citation (:citation),
// Logistics (:logistics), Advisor (:advisor), Health (:health), People (:people), Project
// (:project) and Maintenance (:maintenance) as library modules in one process with shared storage, which is what makes a true cross-app
// "back up everything into one zip / restore from it" possible without any inter-process plumbing.
// The home screen picks an app to open and drives backup/restore. New suite apps plug in here.
// ---------------------------------------------------------------------------------------------
// Version and signing — both come from *outside* this file, so a release is a git tag and nothing
// else. See docs/RELEASING.md.
//
// `lifeops.versionName` is passed by the release workflow as the tag with its leading "v" stripped
// (v1.4.2 -> "1.4.2"). Locally nobody passes it, so a developer build is "0.0.0-dev" / code 1 —
// deliberately *lower* than any real release, so a hand-built debug APK never looks newer than the
// release it was built from and the in-app updater still offers the upgrade.
val releaseVersionName = providers.gradleProperty("lifeops.versionName").orNull ?: "0.0.0-dev"

/**
 * Android's versionCode is a single monotonically increasing int and it is what the OS compares
 * when deciding whether an APK is an upgrade or a downgrade. Deriving it from the semver tag —
 * major*1_000_000 + minor*1_000 + patch — keeps it monotonic without any state kept between builds
 * (a CI run number is not: re-running a job, or tagging an older commit, hands out the wrong order).
 * Room for 999 minors and 999 patches, and the whole int stays well under 2_100_000_000.
 *
 * Anything that isn't a plain x.y.z is code 1, and so is the "0.0.0-dev" default — the arithmetic
 * gives it 0, which AGP rejects outright, and 1 is the right answer anyway: lower than every real
 * release, so a hand-built APK is always upgradeable by one.
 */
fun versionCodeOf(name: String): Int {
    val core = name.substringBefore('-').substringBefore('+')
    val parts = core.split('.')
    if (parts.size != 3) return 1
    val (major, minor, patch) = parts.map { it.toIntOrNull() ?: return 1 }
    if (major < 0 || minor !in 0..999 || patch !in 0..999) return 1
    return (major * 1_000_000 + minor * 1_000 + patch).coerceAtLeast(1)
}

/**
 * The release keystore, supplied by the workflow through the environment (never committed). All
 * four values must be present or there is no release signing config at all — a half-configured
 * signing block fails deep inside the packaging task with a message that says nothing useful, and
 * an unsigned release APK is a far clearer symptom than a mysterious build failure.
 */
val keystorePath: String? = System.getenv("LIFEOPS_KEYSTORE_FILE")
val keystorePassword: String? = System.getenv("LIFEOPS_KEYSTORE_PASSWORD")
val keystoreAlias: String? = System.getenv("LIFEOPS_KEY_ALIAS")
val keystoreAliasPassword: String? = System.getenv("LIFEOPS_KEY_PASSWORD")
val hasReleaseKeystore = listOf(keystorePath, keystorePassword, keystoreAlias, keystoreAliasPassword)
    .all { !it.isNullOrBlank() } && file(keystorePath!!).exists()

android {
    namespace = "com.operations.sandbox"
    // Android 16. Every module in the suite is pinned to the same three numbers; they are repeated
    // per module rather than centralised because AGP wants them in each `android` block, and a
    // module that drifted would be a module whose Kotlin compiles against a different framework
    // than the one it ships with.
    compileSdk = 36

    defaultConfig {
        applicationId = "com.operations.sandbox"
        // Android 14, and the reason is one feature: a third-party app can hold passkeys only
        // through Credential Manager's provider API, which does not exist below it. Everything else
        // here ran happily on 26, and for a long time that floor cost nothing — but a vault that
        // keeps passkeys on some phones and explains why it cannot on others is a worse thing to
        // own than one that simply requires a phone from 2023.
        //
        // Not higher than 34, deliberately. Nothing in this suite uses an API above it, so 35 or 36
        // would buy no code and would only narrow who can install — and the household is more than
        // one phone.
        minSdk = 34
        targetSdk = 36
        versionCode = versionCodeOf(releaseVersionName)
        versionName = releaseVersionName

        // The updater asks GitHub which release is newest. Baked in rather than hardcoded in Kotlin
        // so a fork changes one line here and nothing in the app's source.
        buildConfigField("String", "UPDATE_REPO", "\"bvi34/LifeOps\"")
    }

    signingConfigs {
        // Only declared when the environment actually carries a keystore. On a developer machine
        // this block is empty and `assembleRelease` produces an unsigned APK, exactly as before.
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = keystorePassword
                keyAlias = keystoreAlias
                keyPassword = keystoreAliasPassword
                // v1 was what let the APK install on API 26-27 phones. The floor is 34 now, where
                // v2 has been mandatory for years, so v1 buys nothing and costs a second signature
                // over every entry in the zip. v2/v3 are what the platform actually verifies.
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")

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
        // The updater reads BuildConfig.VERSION_NAME and BuildConfig.UPDATE_REPO.
        buildConfig = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    // The suite's shared appearance — one theme, one store, this app's colour identity in it.
    implementation(project(":suiteui"))
    // The hosted apps (now libraries) and the shared backup engine.
    implementation(project(":lifeops"))
    implementation(project(":citation"))
    implementation(project(":logistics"))
    implementation(project(":advisor"))
    implementation(project(":health"))
    implementation(project(":people"))
    implementation(project(":project"))
    implementation(project(":maintenance"))
    implementation(project(":finance"))
    implementation(project(":repository"))
    // Secrets, the vault. The sandbox is the only module that names it: it installs the app (which
    // registers the broker every other app reads credentials through) and registers its backup
    // contributor. Nothing else in the suite depends on this module — see the note in settings.gradle.
    implementation(project(":secrets"))
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
    // Keystore-backed storage for the updater's GitHub token and the scheduled backup's Azure
    // signature — credentials, so not left in a plain app-private file. Already used elsewhere in
    // the suite for the same reason.
    implementation(libs.androidx.security.crypto)
    // The scheduled cloud backup. The container has had no background work of its own until now —
    // the updater is deliberately launch-time only — but an archive that has to happen whether or
    // not anybody opens the app is exactly what WorkManager is for, and LifeOps and Citation
    // already bring it into this process for their own jobs.
    implementation(libs.androidx.work.runtime.ktx)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation("junit:junit:4.13.2")
    // org.json ships *in* Android, but the JVM unit-test classpath only has the stub android.jar,
    // whose methods all throw. The real implementation here is what lets ReleaseFeed's parsing be
    // tested off-device, the way the suite tests the rest of its logic.
    testImplementation("org.json:json:20240303")
    // The shell now holds a credential of its own and mirrors it into the vault, and that is not a
    // thing that can be tested by reasoning about it: what is being tested is what a real
    // SharedPreferences does when a restore has emptied it. Robolectric runs it on the JVM, the way
    // :secrets and :finance test the same seam from their side.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    // Test-only: `BackupCoverageTest` opens every hosted app's database through that app's own
    // singleton, which is the whole point of it — a census measured against paths this repository
    // spelled out by hand would only prove the list matches itself. Those singletons return Room
    // types, and the hosted apps expose Room as `implementation`, so it reaches the shell's test
    // classpath here and nowhere else. Nothing in :app's own source uses Room.
    testImplementation(libs.androidx.room.runtime)
}
