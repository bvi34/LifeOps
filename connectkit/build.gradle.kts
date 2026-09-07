plugins {
    alias(libs.plugins.kotlin.jvm)
}

// :connectkit — the suite's *address contract*, as a pure JVM Kotlin library.
//
// Same discipline as :core, :backupkit and :suitekit: everything that decides how one part of the
// suite calls another by name — the five-segment address, the payload, the registry, the dispatcher
// that turns every failure into a typed result — is framework-independent and unit-testable on the
// JVM. An app supplies routes and the data behind them; nothing here knows what a task or a document
// is.
//
// It lived inside :lifeops until three apps wanted it. That was fine while LifeOps was the only one
// serving addresses and workable while Project was the second (Project already depends on :lifeops,
// because a dated card publishes itself onto the week). It stopped being workable at Repository,
// whose whole architecture is that the dependency arrow points *into* it and never out: a shelf that
// had to depend on the planner to answer "where is the warranty" would be the wrong shape, and the
// core sitting inside one app is exactly what stops any other one serving routes at all.
//
// So the machinery is here and the *routes* stay with whoever owns the data. One convention across
// the suite; one dispatcher per app.
dependencies {
    testImplementation("junit:junit:4.13.2")
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
