plugins {
    // :securestore — where the suite keeps a credential on *this* phone: a SharedPreferences whose
    // values are sealed with an Android Keystore key that belongs to that one file.
    //
    // It replaced seven hand-rolled EncryptedSharedPreferences stores (Citation's two, Finance's,
    // Secrets' device shortcut, Utilities' session key, the container's update token and backup
    // signature). Those all shared androidx.security's single default master key, and each one's
    // recovery path deleted it — so one unreadable file could take every other store's key with it.
    // Here each file has its own key, and an unreadable one is cleared on its own.
    //
    // The sealing itself (AES-GCM, the value bound to its file and name) is plain javax.crypto and is
    // tested on the JVM with a software key; only where the key lives is Android.
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.operations.securestore"
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

    testOptions {
        // The store logs its recoveries with android.util.Log, which is a stub on the JVM; the tests
        // are about what the store does, not what it says, so the stub is allowed to do nothing.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Only to read an EncryptedSharedPreferences file once, on the way to this format — see
    // `LegacyStore`. Nothing new is written with it. It can go once every phone has opened each
    // store once since this module shipped.
    implementation(libs.androidx.security.crypto)

    testImplementation("junit:junit:4.13.2")
}
