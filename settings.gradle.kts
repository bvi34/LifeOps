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
// hub every other app opens through. LifeOps and Citation are library modules it hosts; the shared,
// JVM-tested backup format/engine is `:backupkit`.
include(":app")
include(":lifeops")
include(":citation")
include(":core")
include(":backupkit")
