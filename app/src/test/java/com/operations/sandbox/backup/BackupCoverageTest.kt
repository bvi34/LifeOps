package com.operations.sandbox.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.advisor.app.data.db.AdvisorDatabase
import com.advisor.app.data.identity.IdentityStore
import com.advisor.app.data.memory.AdvisorMemoryDatabase
import com.advisor.app.data.profile.ProfileStore
import com.advisor.app.data.prompt.SystemPromptStore
import com.citation.app.audio.SpeechSettingsStore
import com.citation.app.data.db.CitationDatabase
import com.finance.app.data.db.FinanceDatabase
import com.finance.app.data.prefs.FinancePrefs
import com.health.app.data.db.HealthDatabase
import com.health.app.data.prefs.HealthPrefs
import com.health.app.data.store.CardImageStore
import com.health.app.data.store.DocumentStore
import com.lifeops.app.data.db.LifeOpsDatabase
import com.logistics.app.data.db.LogisticsDatabase
import com.logistics.app.data.prefs.LogisticsPrefs
import com.logistics.app.data.store.RecipeShotStore
import com.maintenance.app.data.db.MaintenanceDatabase
import com.maintenance.app.data.prefs.MaintenancePrefs
import com.operations.backupkit.AppId
import com.operations.backupkit.BackupEngine
import com.operations.sandbox.BackupCenter
import com.operations.suite.ui.SuiteAppearanceStore
import com.people.app.data.db.PeopleDatabase
import com.people.app.data.prefs.PartnerPrefs
import com.people.app.data.prefs.PeoplePrefs
import com.project.app.data.db.ProjectDatabase
import com.project.app.data.prefs.ProjectPrefs
import com.repository.app.data.db.RepositoryDatabase
import com.repository.app.data.prefs.RepositoryPrefs
import com.repository.app.data.store.DocumentFiles
import com.secrets.app.data.SecretsPrefs
import com.secrets.app.data.VaultFileStore
import com.utilities.app.data.FontFiles
import com.utilities.app.data.LexiconStore
import com.utilities.app.data.LookStore
import com.utilities.app.messages.MessagePrefs
import com.utilities.app.messages.OutboxStore
import com.utilities.app.messages.logic.OutboxEntry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Does "Full Backup" actually mean *full*?
 *
 * Every contributor says it copies the app whole, and every one of them is written to. What nothing
 * checked until now is the claim across the suite: that the twelve slices together account for
 * **everything the suite has put on this phone**. That is not a property any single contributor can
 * assert, because the failure mode is by definition the file nobody thought about — a database added
 * to an app whose contributor was never updated, a store that writes beside the one that is copied,
 * a preferences file whose name slipped outside its app's prefix.
 *
 * So this test takes a census. It makes every app put its real files on disk — databases through
 * each app's *own* singleton, preferences through each app's *own* file-name constant, owned files
 * through each app's *own* store — takes one archive of everything, and then walks the data
 * directory asserting that each file is either **in the archive** or **on a written list of things
 * deliberately left out, with the reason attached**.
 *
 * The point of the second half is that it is a list somebody has to edit. A new file that nobody
 * carries fails here, and the fix is either to carry it or to say in one line why it should not be —
 * which is exactly the conversation that otherwise happens on the day of a restore.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application rather than the suite's own: installing twelve apps would schedule their
// background work, and what is under test is the contributors, not the start-up sequence.
@Config(sdk = [34], application = android.app.Application::class)
class BackupCoverageTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val center by lazy { BackupCenter(context, SANDBOX_VERSION) }

    @Before
    fun setUp() {
        seedTheWholeSuite()
    }

    @Test
    fun `every app the archive can name has exactly one contributor`() {
        val covered = center.contributors.map { it.appId }
        assertEquals("an app with no contributor is an app with no backup", AppId.entries.toSet(), covered.toSet())
        assertEquals("two contributors for one app would write over each other", AppId.entries.size, covered.size)
        // A version of 0 in the manifest tells a future restore nothing about what it is reading.
        assertTrue(center.contributors.all { it.dataVersion > 0 })
        assertTrue(center.contributors.all { it.displayName.isNotBlank() })
    }

    @Test
    fun `a full backup carries every file the suite owns, or says why not`() {
        val archive = fullBackup()
        val carried = entries(archive)
        // Indexed by file name and content: an entry is "the same file" as one on disk when both
        // agree, which is what a restore will actually put back.
        val carriedFiles: Map<String, List<ByteArray>> = carried.entries
            .groupBy({ it.key.substringAfterLast('/') }, { it.value })

        val missed = dataDirCensus().filter { file ->
            val rel = file.relativeTo(dataDir).invariantSeparatorsPath
            if (EXCLUDED.any { (path, _) -> rel.startsWith(path) }) return@filter false
            val bytes = file.readBytes()
            carriedFiles[file.name].orEmpty().none { it.contentEquals(bytes) }
        }

        assertTrue(
            "these files are on disk, belong to the suite, and no contributor carries them — " +
                "either add them to their app's contributor or add them to EXCLUDED with the " +
                "reason: ${missed.map { it.relativeTo(dataDir).invariantSeparatorsPath }}",
            missed.isEmpty()
        )
    }

    @Test
    fun `the manifest names every app that actually contributed`() {
        val manifest = BackupEngine.readManifest(ByteArrayInputStream(fullBackup()))!!
        assertEquals(AppId.entries.map { it.key }.toSet(), manifest.apps.map { it.appId }.toSet())
        assertEquals(SANDBOX_VERSION, manifest.sandboxVersion)
        // Every app's slice names the entries it wrote, and every one of those is really in the zip.
        val carried = entries(fullBackup()).keys
        manifest.apps.forEach { app ->
            app.entries.forEach { entry ->
                assertTrue("${app.appId}/$entry is in the manifest but not in the zip", "${app.appId}/$entry" in carried)
            }
        }
    }

    @Test
    fun `Health and Repository keep their paperwork in one directory, so each carries the other's`() {
        // Not a bug, but not obvious either, and worth a test that fails if either app moves: the
        // suite is one process and one package, so Health's `filesDir/documents` and Repository's
        // `filesDir/documents` are the same folder. Each contributor sweeps it whole, so every
        // document is in the archive twice — once under each app — and restoring either app brings
        // back both apps' files (the rows that name them travel separately, so the other app's are
        // simply unreferenced until its own slice is restored too).
        val carried = entries(fullBackup()).keys
        assertTrue("health/documents/mortgage-statement.pdf" in carried)
        assertTrue("repository/documents/after-visit-summary.pdf" in carried)
        assertEquals(DocumentStore.DIR_NAME, DocumentFiles.DIR_NAME)
    }

    @Test
    fun `everything the archive carries comes back byte for byte`() {
        val archive = fullBackup()
        val carried = entries(archive).filterKeys { it != BackupEngine.MANIFEST_ENTRY }

        // The state a new phone is in: the data is gone, the archive is not.
        wipeTheSuite()

        runBlocking { center.restore(AppId.entries.toSet(), ByteArrayInputStream(archive)) }

        val lost = carried.filter { (entry, bytes) ->
            val file = onDisk(entry) ?: return@filter false
            !file.exists() || !file.readBytes().contentEquals(bytes)
        }
        assertTrue("restored from the archive and these did not come back: ${lost.keys}", lost.isEmpty())
    }

    // -----------------------------------------------------------------------------------------
    // The suite, on disk
    // -----------------------------------------------------------------------------------------

    /**
     * Make every app write what it owns.
     *
     * Deliberately routed through each app's own declarations — `XDatabase.getInstance`,
     * `XPrefs.FILE_NAME`, `XStore.DIR_NAME` — rather than through paths spelled out here. A census
     * measured against a list this file invented would only ever prove that the list matches itself;
     * measured against what the apps themselves say, it catches the database somebody adds next year.
     */
    private fun seedTheWholeSuite() {
        LifeOpsDatabase.getInstance(context).openHelper.writableDatabase
        CitationDatabase.get(context).openHelper.writableDatabase
        LogisticsDatabase.getInstance(context).openHelper.writableDatabase
        AdvisorDatabase.getInstance(context).openHelper.writableDatabase
        AdvisorMemoryDatabase.getInstance(context).openHelper.writableDatabase
        HealthDatabase.getInstance(context).openHelper.writableDatabase
        PeopleDatabase.getInstance(context).openHelper.writableDatabase
        ProjectDatabase.getInstance(context).openHelper.writableDatabase
        MaintenanceDatabase.getInstance(context).openHelper.writableDatabase
        FinanceDatabase.getInstance(context).openHelper.writableDatabase
        RepositoryDatabase.getInstance(context).openHelper.writableDatabase

        // Preferences. LifeOps' two are named here because it is the one app that keeps its file
        // names private to itself; every other app's come from its own prefs class.
        listOf(
            "lifeops_prefs",
            "lifeops_settings",
            HealthPrefs.FILE_NAME,
            LogisticsPrefs.FILE_NAME,
            MaintenancePrefs.FILE_NAME,
            PeoplePrefs.FILE_NAME,
            PartnerPrefs.FILE_NAME,
            ProjectPrefs.FILE_NAME
        ).forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().putString("seeded", name).commit()
        }
        // Utilities' appearance document, written the way the app writes it. Its two takeovers are
        // the keyboard and the messaging app, and neither owns any data worth carrying beyond this
        // file and the word list below — the texts are Android's, in the platform's own provider.
        LookStore.get(context).updateKeyboard { it.copy(numberRow = true) }
        // …and the picture-message settings, which are a second file under the same prefix. The
        // prefix is the whole mechanism by which the contributor decides what travels, so a second
        // file exercising it is worth having.
        MessagePrefs(context).autoDownloadRoaming = true

        // Finance and Secrets keep their file names to themselves, so their preferences are written
        // the way the apps write them — through the class that owns the file. Repository's are here
        // for the same reason and one more: they are on the excluded list, and a line on that list
        // that is never exercised is a line nobody has checked.
        FinancePrefs(context).hasPublishedTasks = true
        SecretsPrefs(context).autoLockMinutes = 3
        RepositoryPrefs(context).rememberFolder(com.repository.app.logic.Drive.GOOGLE_DRIVE, "content://tree/x", "Drive")
        // The suite's appearance, which belongs to the container rather than to any hosted app —
        // also on the excluded list, and also worth being a fact rather than an assumption.
        SuiteAppearanceStore.get(context).setAccent(AppId.LIFEOPS, "#ff0000")

        // The files each app owns, in the directory each app's own store names.
        write(File(context.filesDir, "sovereign/library"), "book.epub")
        write(File(context.filesDir, "sovereign/sync"), "envelope.json")
        write(File(context.filesDir, RecipeShotStore.DIR_NAME), "recipe-shot.jpg")
        write(File(context.filesDir, CardImageStore.DIR_NAME), "insurance-card.jpg")
        write(File(context.filesDir, DocumentStore.DIR_NAME), "after-visit-summary.pdf")
        write(File(context.filesDir, DocumentFiles.DIR_NAME), "mortgage-statement.pdf")
        IdentityStore.file(context).also { it.parentFile?.mkdirs() }.writeText("""{"name":"seed"}""")
        SystemPromptStore.file(context).also { it.parentFile?.mkdirs() }.writeText("be brief")
        write(ProfileStore.dir(context), "user.json")
        // Citation's narrator settings: a file in `filesDir` beside the sovereign sweep rather than
        // inside it, which is precisely why it went uncarried until this census found it.
        File(context.filesDir, SpeechSettingsStore.FILE_NAME).writeText("""{"rate":1.4}""")
        // Utilities' own directory: the keyboard's learned word list, and a font file the household
        // pointed at. The font is here rather than assumed, because a copied font whose bytes did
        // not travel is a face that silently stops working on the new phone — which is the entire
        // reason `FontFiles` copies it in rather than keeping the picker's URI.
        write(File(context.filesDir, LexiconStore.DIR_NAME), LexiconStore.FILE_NAME)
        write(File(context.filesDir, FontFiles.DIR_NAME), "opendyslexic.font")
        // The vault, as a file: "OPSVAULT" is the magic its own reader sniffs for, and the sealed
        // body past it is :secrets' business rather than this test's.
        VaultFileStore(context).vaultFile.also { it.parentFile?.mkdirs() }
            .writeBytes("OPSVAULT".toByteArray() + ByteArray(64) { it.toByte() })

        // And the things that must NOT travel, seeded so their absence from the archive is a fact
        // this test establishes rather than an assumption it makes.
        listOf("secure_finance_access", "oreilly_access", "opds_catalog_access", "secure_secrets_device")
            .forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().putString("token", "SECRET").commit() }
        // Utilities' outbox, which must not travel for a different reason than the credentials do:
        // it is a list of claims that the platform's message store is missing something, and on a
        // restored phone every one of those claims is false.
        OutboxStore(context).add(42L, OutboxEntry(address = "5550109999", body = "on my way", at = 1L))
    }

    /** Delete everything the suite keeps, as a new phone would have it. */
    private fun wipeTheSuite() {
        LifeOpsDatabase.closeInstance()
        CitationDatabase.closeInstance()
        LogisticsDatabase.closeInstance()
        AdvisorDatabase.closeInstance()
        AdvisorMemoryDatabase.closeInstance()
        HealthDatabase.closeInstance()
        PeopleDatabase.closeInstance()
        ProjectDatabase.closeInstance()
        MaintenanceDatabase.closeInstance()
        FinanceDatabase.closeInstance()
        RepositoryDatabase.closeInstance()
        File(dataDir, "databases").deleteRecursively()
        File(dataDir, "shared_prefs").deleteRecursively()
        context.filesDir.deleteRecursively()
    }

    private fun write(dir: File, name: String) {
        dir.mkdirs()
        File(dir, name).writeBytes(name.toByteArray() + ByteArray(32) { (it * 7).toByte() })
    }

    private val dataDir: File get() = File(context.applicationInfo.dataDir)

    /**
     * Every file the suite has on this phone, less the ones the operating system writes for its own
     * purposes (a journal beside a database is not a file anybody backs up; it is checkpointed into
     * the database before the copy, which is what makes the copy whole).
     */
    private fun dataDirCensus(): List<File> = dataDir.walkTopDown()
        .filter { it.isFile }
        .filterNot { it.name.endsWith("-journal") || it.name.endsWith("-wal") || it.name.endsWith("-shm") }
        .toList()

    // -----------------------------------------------------------------------------------------
    // The archive
    // -----------------------------------------------------------------------------------------

    private fun fullBackup(): ByteArray = ByteArrayOutputStream().also { out ->
        runBlocking { center.backup(AppId.entries.toSet(), out) }
    }.toByteArray()

    private fun entries(archive: ByteArray): Map<String, ByteArray> {
        val found = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) found[entry.name] = zip.readBytes()
                entry = zip.nextEntry
            }
        }
        return found
    }

    /**
     * Where one archive entry belongs on disk — the same answer each contributor's restore reaches,
     * written once so the round-trip can check every entry rather than a sample.
     */
    private fun onDisk(entry: String): File? {
        val app = entry.substringBefore('/')
        val rel = entry.substringAfter('/')
        return when {
            rel.startsWith("shared_prefs/") -> File(dataDir, "shared_prefs/${rel.substringAfterLast('/')}")
            rel.endsWith(".db") -> context.getDatabasePath(rel)
            app == AppId.SECRETS.key && rel == "vault.opsv" -> VaultFileStore(context).vaultFile
            app == AppId.ADVISOR.key && rel == "identity.json" -> IdentityStore.file(context)
            app == AppId.ADVISOR.key && rel == "system-prompt.txt" -> SystemPromptStore.file(context)
            app == AppId.ADVISOR.key && rel.startsWith("profiles/") ->
                File(ProfileStore.dir(context), rel.substringAfterLast('/'))
            else -> File(context.filesDir, rel)
        }
    }

    private companion object {

        const val SANDBOX_VERSION = "1.2.3-test"

        /**
         * What no archive carries, and why. Every line here is a decision somebody made on purpose;
         * a file that is not in the archive and not on this list fails the census.
         */
        val EXCLUDED = listOf(
            "cache/" to
                "the cache is regenerable by definition — Citation's Royal Road bodies, Advisor's vector index",
            "code_cache/" to "the runtime's, not the suite's",
            "no_backup/" to "the platform's own opt-out directory",
            "files/models/" to
                "Advisor's downloaded language and embedding models — hundreds of megabytes, and " +
                    "re-downloadable from the same place they came from",
            "files/exports/" to
                "copies the household asked to be made for somewhere else; the originals are carried",
            "shared_prefs/secure_finance_access" to
                "Finance's credentials: kept out of the archive on purpose, carried by the vault instead",
            "shared_prefs/oreilly_access" to "Citation's O'Reilly sign-in — same bargain",
            "shared_prefs/opds_catalog_access" to "Citation's catalogue sign-ins — same bargain",
            "shared_prefs/secure_secrets_device" to
                "the vault key wrapped by this phone's Keystore: an archive holding both it and the " +
                    "sealed vault would be an archive holding the vault in plaintext",
            "shared_prefs/sandbox_" to
                "the container's own settings — the updater's and the scheduled cloud backup's, " +
                    "including their credential stores, which the vault carries instead",
            "shared_prefs/operations_suite_appearance" to
                "the suite's look (preset, palette, per-app accents, wallpaper). It belongs to the " +
                    "container rather than to any hosted app, and the archive has no slice for the " +
                    "container — so it is re-chosen after a restore",
            "shared_prefs/logistics_prefs" to
                "a display toggle — whether empty items are hidden — which `LogisticsBackupContributor` " +
                    "leaves out on purpose; nothing a household would miss on a new phone",
            "shared_prefs/outbox_utilities" to
                "Utilities' record of messages it sent that the platform's store did not have yet. " +
                    "Restored onto a new phone every entry is a claim that is false by the time it " +
                    "arrives, so the file is deliberately named outside the prefix its contributor " +
                    "sweeps",
            "shared_prefs/repository_prefs" to
                "the last drive and folder a transfer used: SAF grants that do not survive a " +
                    "reinstall, so restoring them would name folders this phone cannot open"
        )
    }
}
