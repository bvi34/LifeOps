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
//
// It is the third app to serve connection routes, and the arrow is why :connectkit exists: the
// address machinery lived inside :lifeops, and a shelf that had to depend on the planner to answer
// "where is the warranty" would be the wrong shape. Its routes read and correct captions — they
// cannot file, delete or export a document, and each of those addresses is asserted absent.
include(":repository")
// Finance is the suite's picture of the household's money: the accounts as the institutions report
// them, the balances, the transactions behind them, and — the part that earns the app — the dates
// things fall due. It reaches two providers, both with credentials the household types in itself:
// Plaid (which is how USAA and most retail banks are reachable at all) and Mercury's own API. It is
// read-only by construction — there is no transfer call anywhere in the module.
//
// Like Project and Maintenance it is not a peer on the sync spine (nothing else in the suite writes
// to a balance) and it writes *outward* to :lifeops for the same reason they do: a bill with a due
// date publishes itself onto the LifeOps week as a task dated the day it falls due, and takes the
// tick back. It also depends on :repository, one way, because the paperwork money arrives with —
// the statement, the payoff letter — is a document the household filed rather than something a
// finance app should keep a second copy of.
include(":finance")
// Secrets is the suite's vault: one encrypted file, one master passphrase, and the credentials every
// other app used to keep in a device-bound store that a restore could not bring back. It is the one
// app whose backup payload deliberately contains credentials — safely, because the key that opens
// them is a passphrase in somebody's head rather than anything the archive or the phone holds.
//
// The dependency arrow points *out* of it and never in: no app depends on :secrets. They depend on
// :vaultkit for the ref and the broker interface, and find the implementation through a
// registration the sandbox makes at start-up (SecretsAccess). A bank client that had to pull in a
// password manager's UI to read its own token would be the wrong shape.
include(":secrets")
// Utilities is the app that replaces pieces of the *phone* rather than providing a service: the
// keyboard, and the messaging app. Its reason for existing is a dependency it does not have — no
// INTERNET permission and no HTTP client on its classpath — because a keyboard sees every password
// typed on a phone and a messenger sees every conversation, and the reason anybody replaces the
// ones a handset ships with is that those have somewhere to send what they see.
//
// It is not a peer on the sync spine, it serves no connection routes, and it owns no data: the
// texts stay in Android's own Telephony provider, where they have always been, and this app draws a
// window onto them. That is what makes each takeover reversible — handing Messages back to the
// carrier's app loses nothing, because nothing moved — and it is why its backup slice is a few
// kilobytes of appearance settings and a learned word list rather than a copy of a message history.
//
// The arrow points *out* of it and never in, like Secrets': it depends on :suiteui and :suitekit
// for the shared theme and the one colour picker, and on :backupkit for its AppId and its slice.
// Its own two surfaces deliberately do *not* follow the suite's theme — a keyboard is seen beside
// everybody else's apps rather than beside Logistics — so it carries its own look, borrowed in
// design from Citation's reader settings and copied rather than depended on (see UtilityLook).
include(":utilities")
include(":core")
include(":backupkit")
// The suite's address contract: `/v1/{application}/{connection}/{resource}/{action}`, the payload,
// the registry and the dispatcher — pure JVM, and knowing nothing about what a task or a document
// is. It lived inside :lifeops while LifeOps was the only app serving addresses; it is here because
// Repository is the third, and unlike Project it cannot depend on :lifeops without inverting the one
// arrow it is built around. Routes stay with whoever owns the data.
include(":connectkit")
// The suite's *secret-keeping contract*: the vault file format, the key derivation, the document
// inside it, the address a credential is filed under, the generator and the audit — pure JVM, and
// with no Android Keystore anywhere in it. That last part is the point rather than a coincidence: a
// key bound to one phone's hardware dies with that phone, which is exactly the failure the Secrets
// app exists to fix, so the root of trust here is a passphrase the household knows.
include(":vaultkit")
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
include(":securestore")
