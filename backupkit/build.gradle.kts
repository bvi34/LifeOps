plugins {
    alias(libs.plugins.kotlin.jvm)
}

// :backupkit — the Operations Sandbox backup format and engine, as a *pure JVM Kotlin* library.
//
// Same discipline as :core: everything about the cross-app backup archive (the manifest model and
// its codec, the zip layout, the streaming writer/reader, the engine that walks contributors) is
// framework-independent and unit-testable on the JVM. The Android side (:app, :lifeops, :citation)
// only supplies BackupContributor implementations that read/write real databases and files; the
// archive mechanics live here, tested without an emulator.
dependencies {
    // Gson is pure JVM (used only for the manifest codec), so :backupkit stays Android-free.
    implementation(libs.gson)

    testImplementation("junit:junit:4.13.2")
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
