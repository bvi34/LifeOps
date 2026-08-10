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
// hub every other app opens through. LifeOps, Citation, Logistics and Advisor are library modules it
// hosts; the shared, JVM-tested backup format/engine is `:backupkit`. Advisor is the suite's RAG
// assistant — it reads the other apps' data (permission-gated) to answer grounded questions.
include(":app")
include(":lifeops")
include(":citation")
include(":logistics")
include(":advisor")
include(":core")
include(":backupkit")
