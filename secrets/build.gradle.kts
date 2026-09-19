plugins {
    // Secrets — the suite's vault. Like the other ten it is a *library* module consumed by the
    // Operations Sandbox container app (:app); it owns no launcher and no Application of its own.
    //
    // What it owns is the one store in this suite that is safe to put in a backup. Every other app
    // keeps its credentials behind a hardware-bound Keystore key and leaves them out of the archive
    // on purpose, which is correct and which costs the household every credential it has the day it
    // restores onto a new phone. Secrets is the answer to that: one encrypted file, one passphrase
    // that lives in somebody's head rather than in a TEE, and a broker the other apps mirror their
    // credentials through.
    //
    // Two things it deliberately is not:
    //
    //  - **Networked.** There is no INTERNET permission in this module's manifest and no HTTP client
    //    on its classpath. No sync, no breach lookup, no "k-anonymous" hash prefix sent to anybody.
    //    A password manager that can talk to a server is a password manager whose worst day involves
    //    somebody else's server. The two features that arrived with a permission and an export —
    //    the QR scanner and autofill — are both local: zxing decodes in this process, and autofill
    //    answers the platform. Neither is a network feature wearing a hat.
    //  - **Addressable by another app.** It serves no connection routes. The suite has an address
    //    contract (:connectkit) and three apps answer on it; this one does not, because a vault that
    //    can be *called* is a vault with a surface. The ways in are exactly two, and neither belongs
    //    to another app: [com.operations.vaultkit.SecretsBroker], which reads back what an app itself
    //    filed and cannot list anything, and the autofill service, which only the platform can bind
    //    (BIND_AUTOFILL_SERVICE) and which answers nothing the rules in AutofillMatch did not earn.
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.secrets.app"
    compileSdk = 36

    defaultConfig {
        minSdk = 34
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
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    // The format, the crypto and the contract — everything decidable without a device (see :vaultkit).
    // `api` rather than `implementation`: the broker and its ref types are this module's public
    // surface as far as the sandbox is concerned, and :app registers the one with the other.
    api(project(":vaultkit"))
    // The suite's shared appearance — one theme, one store, this app's colour identity in it.
    implementation(project(":suiteui"))
    implementation(project(":suitekit"))
    // AppId, for the archive and for saying which app a mirrored credential belongs to.
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
    implementation(libs.kotlinx.coroutines.android)
    // The Keystore, used for exactly one thing: wrapping the vault key for the *convenience* unlock,
    // so a fingerprint can stand in for typing thirty characters. It is never the root of trust —
    // see data/DeviceUnlock, which is the file that explains why that distinction is the whole
    // point of this app.
    implementation(libs.androidx.security.crypto)
    // Reading the QR code a site shows when it hands over a second-factor seed. `zxing-core` is
    // plain Java — the decoder, with no Android in it and no network anywhere near it — and
    // `zxing-android-embedded` is the camera half. People already takes both for partner pairing,
    // which is why they are in the catalogue; the scanning activity here is a subclass of that
    // library's, adding FLAG_SECURE (see ui/scan/SecureCaptureActivity).
    implementation(libs.zxing.core)
    implementation(libs.zxing.android.embedded)
    // Credential Manager's provider half — how a third-party app holds passkeys. Android 14 only,
    // which is the floor for that feature and nothing else here; see passkey/.
    implementation(libs.androidx.credentials)
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // The file store and the lock state are the parts that cannot be tested by reasoning about them
    // — what they are being tested for is what a torn write leaves on disk and what a restore finds
    // — so they run on the JVM through Robolectric, like Finance's database does.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}
