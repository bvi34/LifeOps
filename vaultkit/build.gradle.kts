plugins {
    alias(libs.plugins.kotlin.jvm)
}

// :vaultkit — the suite's *secret-keeping contract*, as a pure JVM Kotlin library.
//
// Same discipline as :core, :backupkit, :suitekit and :connectkit: everything that decides how a
// secret is protected — the key derivation, the sealed envelope, the document inside it, the address
// a secret is known by, the generator, the audit rules and the merge a restore performs — is
// framework-independent and unit-testable on the JVM. The Android side (:secrets) only supplies a
// file, a lock screen and a clock.
//
// That split is not tidiness here, it is the point. Cryptography that can only be exercised on a
// device is cryptography nobody exercises: the tests beside this code open a vault with the wrong
// passphrase, flip one bit of a ciphertext, hand it a header claiming a weaker KDF, and assert that
// each of those *fails* — none of which needs an emulator, and none of which anybody would run per
// commit if it did.
//
// Everything here uses only `javax.crypto`, which is on both the JVM and Android. There is no
// dependency on the Android Keystore anywhere in this module, and that is deliberate: a key bound to
// one phone's hardware is a key that dies with the phone, which is exactly the failure this whole
// app exists to fix. The Keystore has a job in :secrets (it wraps the *convenience* unlock), but it
// is never the root of trust — the passphrase is, because a passphrase is the only thing the
// household can carry from a dead phone to a new one.
dependencies {
    // Gson is pure JVM (used only for the vault document codec), so :vaultkit stays Android-free.
    implementation(libs.gson)
    // For AppId — a managed secret says which app it belongs to, and the suite already has one
    // stable identity per hosted app. Same reason :suitekit takes this dependency.
    implementation(project(":backupkit"))

    testImplementation("junit:junit:4.13.2")
    // The refill contract is a suspending one — an app looks its own connection names up in a
    // database before it can title what it files — so its tests need a test dispatcher.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}
