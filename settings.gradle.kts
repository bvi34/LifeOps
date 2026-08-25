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
include(":core")
include(":backupkit")
