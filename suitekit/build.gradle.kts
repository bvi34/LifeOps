plugins {
    alias(libs.plugins.kotlin.jvm)
}

// :suitekit — the suite's *appearance contract*, as a pure JVM Kotlin library.
//
// Same discipline as :core and :backupkit: everything that decides what the suite looks like — the
// presets, the custom palette, each app's colour identity, the ARGB maths that turns those into a
// full colour scheme — is framework-independent and unit-testable on the JVM. The Android side
// (:suiteui) only adapts a resolved [SuiteScheme] to Material 3 and persists a [SuiteAppearance];
// no colour decision is made there.
//
// It depends on :backupkit purely for [com.operations.backupkit.AppId] — the suite already has one
// stable identity per hosted app, and the home screen, the settings and the archive all key off it.
dependencies {
    implementation(project(":backupkit"))
    // Gson is pure JVM (used only for the appearance document codec), so :suitekit stays Android-free.
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
