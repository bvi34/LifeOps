plugins {
    // Utilities — the app that replaces pieces of the phone rather than providing a service.
    //
    // Like the other eleven it is a *library* module consumed by the Operations Sandbox container
    // app (:app); it owns no launcher and no Application of its own. Unlike the other eleven, what
    // it ships is mostly components the *operating system* starts: an input method the platform
    // binds, and the four components that make an app eligible to be the phone's messenger.
    //
    // The defining property of this module is a dependency it does not have. There is no HTTP
    // client on this classpath and no INTERNET permission in its manifest, and that is the whole
    // argument for the app: a keyboard sees every password typed on the phone and a messenger sees
    // every conversation, and the reason to replace the ones that ship with a handset is that those
    // have somewhere to send what they see. This one has nowhere.
    //
    // Two more things it deliberately is not:
    //
    //  - **The owner of any data.** The texts stay in Android's own Telephony provider, where they
    //    have always been; this app draws a window onto them. That is what makes the takeover
    //    reversible — handing Messages back to the carrier's app loses nothing, because nothing was
    //    moved — and it is why the backup slice here is a few kilobytes of appearance settings and
    //    a word list rather than a copy of somebody's message history.
    //  - **Addressable by another app.** It serves no connection routes (:connectkit). The ways in
    //    are the platform's: BIND_INPUT_METHOD, the three messaging permissions only the system
    //    holds, and one `sms:` link activity that takes a number and gives nothing back.
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.utilities.app"
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
    // The suite's shared appearance — one theme, one store, this app's colour identity in it, and
    // the colour picker the look editor reaches for rather than growing its own.
    implementation(project(":suiteui"))
    implementation(project(":suitekit"))
    // AppId, and the backup contract this app's small slice implements.
    implementation(project(":backupkit"))
    // The vault seam, for exactly one thing: the sealed-messaging identity key. It is the one
    // credential this app holds that must survive a new phone and must not sit in an archive, which
    // is the case the vault exists for — the same bargain Finance strikes with its bank tokens. See
    // messages/seal/SealStore.
    implementation(project(":vaultkit"))

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
    // Showing and scanning a safety number. `zxing-core` is plain Java — the encoder and decoder,
    // with no Android in it — and `zxing-android-embedded` is the camera half. Both are already in
    // the catalogue for People's partner pairing and Secrets' second-factor scanner.
    implementation(libs.zxing.core)
    implementation(libs.zxing.android.embedded)
    implementation(libs.kotlinx.coroutines.android)
    // The appearance documents, stored as JSON rather than as thirty preference keys that have to
    // be kept in step with two data classes by hand. Pure JVM, and already in the catalogue.
    implementation(libs.gson)
    debugImplementation(libs.androidx.ui.tooling)

    // Everything decidable without a phone is decided without a phone: the key layouts and the
    // shift/layer machine, the learned-word list, the colour maths the two taken-over surfaces are
    // painted with, and the rule for when a sent message has landed in the platform's store. None
    // of those touch Android, so none of them need Robolectric.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
