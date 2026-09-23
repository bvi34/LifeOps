plugins {
    // Finance — the suite's read-only picture of the household's money. Like LifeOps, Citation,
    // Logistics, Advisor, Health, People, Project, Maintenance and Repository it is a *library*
    // module consumed by the Operations Sandbox container app (:app); it owns no launcher or
    // Application of its own.
    //
    // Finance owns *what the institutions say*: the accounts, their balances, the transactions
    // behind them, the bills that come round and the day each one falls due. Two things it
    // deliberately does not own:
    //
    //  - **Your day.** It says a payment is due on the 14th, never when you will sit down and make
    //    it. Scheduling is LifeOps' job, so a bill is *handed* to that planner as a task dated the
    //    day it falls due and the tick comes back — the same seam Maintenance publishes upkeep on,
    //    for the same reason: a second planner is a second answer to "what am I doing today".
    //  - **Moving money.** Every credential this app holds is asked for read-only and every request
    //    it makes is a GET of something that already happened. There is no code path in this module
    //    that can initiate a transfer, and that is a property of what is written here rather than a
    //    setting somebody could turn off.
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.finance.app"
    compileSdk = 36

    defaultConfig {
        minSdk = 34
        // Code shrinking is the consuming app's (:app) responsibility.
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        // Robolectric needs the merged Android resources to stand a context up; the database tests
        // (schema migrations and the repository's own rules) run on the JVM through it, so
        // `gradle :finance:testDebugUnitTest` still covers the store without a device.
        unitTests.isIncludeAndroidResources = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // The suite's shared appearance — one theme, one store, this app's colour identity in it.
    implementation(project(":suiteui"))
    // Money is cents and the formatting rules are the suite's, not this app's. Named explicitly
    // rather than leaned on through :suiteui, because `implementation` is not transitive and this
    // module's *logic* — which knows nothing about Compose — is what needs SuiteMoney.
    implementation(project(":suitekit"))
    // The Operations Sandbox backup format/engine (pure JVM). Finance supplies a BackupContributor.
    implementation(project(":backupkit"))
    // The vault contract (pure JVM). Finance mirrors every credential it holds into the Secrets
    // vault and reads through to it when its own store comes up empty — which after a restore is
    // every credential at once. It depends on the *contract*, never on :secrets: the implementation
    // is found through a registration the sandbox makes at start-up.
    implementation(project(":vaultkit"))
    // LifeOps, for the week. Finance knows *when* a bill is due; LifeOps is where a week is planned,
    // so a bill publishes itself there as a task dated the day it falls due and takes the tick back
    // (see data/repository/LifeOpsTasks). The dependency points one way only: LifeOps announces
    // completions on a bus and knows nothing about who is listening.
    implementation(project(":lifeops"))
    // Repository, for the paperwork. A statement, a payoff letter, a 1099 — the documents money
    // arrives with are documents the household filed, so Finance keeps no document store of its own:
    // the account page lends the shelf a section (`ui/account/AccountDetailScreen`, one
    // `DocumentsPanel` call) and the rows stay findable from Repository without it knowing they were
    // filed here.
    implementation(project(":repository"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.android)
    // Parsing two JSON APIs (Plaid and Mercury). Pure-JVM, so the parsers stay in logic/ and are
    // unit-tested against captured payloads — the same split Maintenance uses for vPIC and Health
    // for RxNorm.
    implementation(libs.gson)
    // The one dependency Finance has that the other apps mostly don't need: an access token that can
    // read a bank account is the most sensitive string in the suite, so it is kept in
    // a store sealed by its own Android Keystore key rather than in a plain XML file beside the
    // "which tab were you on" state. Citation already takes this for its catalogue logins; this is
    // the stronger version of the same case.
    implementation(project(":securestore"))
    debugImplementation(libs.androidx.ui.tooling)

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    // The store, on the JVM. Everything in logic/ is pure and needs nothing; the database is the
    // one part that cannot be tested by reasoning about it, because what it is being tested for —
    // that a migration produces the schema Room expects, that a cascade really cascades — is
    // SQLite's behaviour and not Kotlin's.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}
