package com.health.app.data.repository

import com.health.app.data.db.dao.HealthDao
import com.health.app.data.prefs.HealthPrefs
import com.people.app.sync.LocalRosterChange

/**
 * Health's composition root: one object the screens are handed, holding one store per thing Health
 * keeps records about.
 *
 * This class decides nothing. It owns no queries, no timestamps and no judgements — it wires the
 * stores together and hands them out. Every judgement is still delegated to the framework-free
 * `logic/` package, and every row still goes through `HealthDao`; what changed is that the code
 * doing so is now grouped by *what it is about* rather than piled into one class.
 *
 * Read a store's own KDoc for what it owns. Two behaviours span several of them and are worth
 * knowing about because they are invisible in the schema:
 *
 *  - **Open episodes capture what happens during them.** Anything logged while a person has an open
 *    illness is filed against it automatically, by [EpisodeFiling]. Nobody remembers to tick "this
 *    is part of the flu" at 3am, and an episode you have to assemble by hand afterwards is one you
 *    never assemble.
 *  - **One open episode per person.** Starting a new one closes the previous, so "how long has this
 *    been going on" always has a single answer. That rule lives in [EpisodeStore].
 */
class HealthRepository(
    dao: HealthDao,
    prefs: HealthPrefs,
    /**
     * How the People sync seam hears that a profile changed here.
     *
     * A local profile edit stamps a new `syncVersion`, but a stamp nobody publishes is a change that
     * sits in the database until the next time Health happens to be opened — by which point the user
     * has usually gone looking for it in People or LifeOps and found the old name. Stamping the
     * version and telling the seam are the same event, so they happen in the same place rather than
     * being remembered separately at each screen.
     *
     * Fires for local edits only. [ProfileStore.applyMergedProfile] and [ProfileStore.createFromPacket]
     * are writes that arrived over the seam and deliberately do not call it — re-publishing them
     * would hand the other peer its own change straight back.
     */
    onProfileEdit: (LocalRosterChange) -> Unit = {},
    /**
     * How the reminder scheduler hears that a medicine's reminder needs re-arming.
     *
     * Called with the medicine's id after anything that can change when it should next nudge: the
     * reminder itself being set or cleared, the medicine being paused, resumed or deleted, and a
     * dose being recorded (which is what moves a "when the next dose is due" reminder). Called with
     * null to mean "re-arm everything", after a restore or when the app starts.
     *
     * A hook rather than a call into WorkManager, for the reason `logic/` exists at all: these
     * stores are JVM-testable and stay that way. The Android half lives in
     * `reminder/MedicationReminderScheduler`, wired up once in [com.health.app.HealthApp].
     */
    onReminderChange: (medicationId: String?) -> Unit = {},
    /**
     * How the card-image store hears that a photo is no longer referenced by anything.
     *
     * A hook for the same reason [onReminderChange] is one: the stores stay JVM-testable, and
     * deleting a file is Android I/O. Called with the file name after the row that pointed at it is
     * gone — never before, because a file deleted ahead of a write that then fails leaves a card
     * pointing at nothing.
     */
    onCardImageDiscarded: (fileName: String) -> Unit = {},
    /**
     * How the document store hears that a stored file is no longer referenced by any row.
     *
     * A hook for the same reason [onCardImageDiscarded] is one: the stores stay JVM-testable and
     * know nothing about disk. Called with the file name *after* the row naming it is gone.
     */
    onDocumentDiscarded: (fileName: String) -> Unit = {}
) {

    /** Which illness a new record belongs to — shared by everything that files one. */
    private val filing = EpisodeFiling(dao)

    /** The bottle-side half of taking a dose — shared by [doses] alone, but neither store's own. */
    private val stock = CabinetStock(dao)

    val profiles = ProfileStore(dao, prefs, onProfileEdit, onDocumentDiscarded)
    val readings = ReadingStore(dao, filing)
    val symptoms = SymptomStore(dao, filing)
    val medications = MedicationStore(dao, onReminderChange)
    val doses = DoseStore(dao, filing, stock, onReminderChange)
    val cabinet = CabinetStore(dao)
    val episodes = EpisodeStore(dao)
    val careNotes = CareNoteStore(dao, filing)
    val history = HistoryStore(dao, prefs)
    val snapshots = SnapshotStore(dao, medications)
    val standingRecord = StandingRecordStore(dao)
    val immunizations = ImmunizationStore(dao)
    val documents = DocumentStore(dao, onDocumentDiscarded)
    val coverage = CoverageStore(dao, onCardImageDiscarded)
    val careTeam = CareTeamStore(dao)
    val networkChecks = NetworkCheckStore(dao)
}
