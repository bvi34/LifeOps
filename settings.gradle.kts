pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "LifeOps"
// :app is the Operations Sandbox container — the single installable application and the central
// hub every other app opens through. LifeOps, Citation, Logistics, Advisor and Health are library
// modules it hosts; the shared, JVM-tested backup format/engine is `:backupkit`. Advisor is the
// suite's RAG assistant — it reads the other apps' data (permission-gated) to answer grounded
// questions. Health is the household health tracker: a profile per person, and the temperatures,
// symptoms, medicines and illnesses recorded against them. People is the household directory the
// suite refers to — it and LifeOps each keep a roster and reconcile over the sync spine.
include(":app")
include(":lifeops")
include(":citation")
include(":logistics")
include(":advisor")
include(":health")
include(":people")
// Project is the document and planning repository: a shelf of projects, each with an outline, its
// documents, its lore, its timeline and the board the work gets done on. Unlike People it is not a
// peer on the sync spine — nothing else in the suite writes to a project, so there is nothing to
// reconcile.
include(":project")
include(":core")
include(":backupkit")
// The suite's appearance: `:suitekit` is the pure-JVM contract (presets, palettes, each app's colour
// identity and the maths that resolves them into a scheme); `:suiteui` is the Compose theme and the
// store behind it that the sandbox settings edit and every hosted app reads.
include(":suitekit")
include(":suiteui")
