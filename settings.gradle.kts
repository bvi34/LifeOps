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
include(":app")
include(":core")
include(":citation")
// Operations Sandbox: the single container app that hosts LifeOps + Citation and drives
// cross-app backup/restore. `:backupkit` is its framework-independent, JVM-tested spine.
include(":backupkit")
include(":sandbox")
