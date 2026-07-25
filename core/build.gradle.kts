plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Citation :core — the sync contract and the reader's framework-independent spine.
//
// This module is deliberately a *pure JVM Kotlin* library, not an Android module. Everything
// Citation needs to reason about content, identity, notes, and the LifeOps sync seam lives here
// with zero Android dependencies, so the whole walking skeleton is unit-testable on the JVM (the
// same discipline LifeOps uses for its `util/` growth + weather logic). The Android reader
// (`:citation`) depends on this module and only adds Room storage, WorkManager jobs, and the
// Compose UI on top.
dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
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
