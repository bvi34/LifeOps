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
// reconcile. It does depend on :repository, one way: a project's *files* — the brief, the contract,
// the reference PDFs — are documents the household filed rather than writing the project is made of,
// so they live on the suite's shelf and are shown on the Docs screen in place.
//
// It also writes *outward* to :lifeops, like Maintenance and for the same reason: a board card with
// a due date publishes itself onto the LifeOps week as a task dated the day it falls due, and takes
// the tick back. That is a one-way module dependency (:project -> :lifeops) plus the bus LifeOps
// announces completions on, not a second planner — Project holds when a thing is *due*, never when
// you will do it.
include(":project")
// Maintenance is the register of what the household owns and what those things need: assets (a
// home, a car, the furnace), the identity each kind is known by (VIN, parcel number, serial), the
// schedules that come round, the log of what was done, and the money — mortgages, loans, policies.
// Like Project it is not a peer on the sync spine: nothing else in the suite writes to an asset. It
// does write *outward*, though — an upkeep plan publishes itself onto the LifeOps week as a task
// dated the day it falls due, and takes the tick back. That is a one-way module dependency
// (:maintenance -> :lifeops) plus a bus LifeOps announces completions on, not a second planner.
include(":maintenance")
// Repository is the suite's shelf: every document the household has been handed — the mortgage
// statement, the manual, the warranty, the title — filed once and reachable two ways. Its own screen
// lists everything without needing to know which app it arrived through; the app that owns the thing
// shows the same documents in place, on the asset or the person they belong to. The dependency arrow
// points into it (:maintenance -> :repository), and an app that already stores its own paperwork
// lends it to the shelf read-only rather than moving it.
include(":repository")
include(":core")
include(":backupkit")
// The suite's appearance and its shared controls: `:suitekit` is the pure-JVM contract (presets,
// palettes, each app's colour identity, the maths that resolves them into a scheme, and the swatch
// palette); `:suiteui` is the Compose theme, the store behind it that the sandbox settings edit and
// every hosted app reads, `ui/pickers` — one colour picker, one date picker, one time picker and one
// when-picker — and `ui/fields` — one text field, one number field, one money field. No app grows
// its own again. What the apps keep is the *rules*: a picker offers everything and asks the app what
// it makes of the choice (see SuiteVerdict), so Health and LifeOps can disagree about the same
// Tuesday without either forking the control.
include(":suitekit")
include(":suiteui")
