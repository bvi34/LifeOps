package com.operations.sandbox.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.advisor.app.data.db.AdvisorDatabase
import com.advisor.app.data.memory.AdvisorMemoryDatabase
import com.citation.app.data.db.CitationDatabase
import com.finance.app.data.db.FinanceDatabase
import com.health.app.data.db.HealthDatabase
import com.lifeops.app.data.db.LifeOpsDatabase
import com.logistics.app.data.db.LogisticsDatabase
import com.maintenance.app.data.db.MaintenanceDatabase
import com.operations.sandbox.R
import com.operations.sandbox.cloud.CloudBackupPrefs
import com.operations.sandbox.update.UpdatePrefs
import com.people.app.data.db.PeopleDatabase
import com.project.app.data.db.ProjectDatabase
import com.repository.app.data.db.RepositoryDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import java.io.File

/**
 * The *other* backup — the one that happens to the household rather than the one they take.
 *
 * `BackupCoverageTest` asks whether the sandbox's own zip is full. This asks the same question of
 * Android's Auto Backup, which runs on its own, off a charger, onto Google's transport, and is what
 * actually restores the suite when somebody sets up a new phone and taps "restore from backup"
 * before they ever hear that this app has an archive of its own.
 *
 * It is a fair question, because the answer used to be no. `backup_rules.xml` was written when the
 * container held two apps and it named their two databases; nine more arrived over the following
 * months and none of them was added, because nothing anywhere failed when one wasn't. The
 * preferences were swept by domain the whole time, so a restored phone came up with everybody's
 * settings, LifeOps' and Citation's data, and nine apps that had forgotten everything — the worst
 * shape this failure can take, because it looks like a working restore.
 *
 * So the rules now take the database domain whole, and this test is the thing that notices if they
 * ever stop. It makes every app create its real database, through the app's own singleton, and then
 * evaluates the shipped rules against what is on the disk. Measuring against a list of names this
 * file invented would only prove the list matches itself; measuring against what the apps actually
 * write is what catches next year's database.
 *
 * The second half asks the opposite question of the same rules — that the credential stores are
 * still kept *out* — because "back up everything" and "never back up a key" are one policy, and a
 * change that widened the first at the cost of the second would otherwise pass.
 */
@RunWith(RobolectricTestRunner::class)
// A plain Application rather than the suite's own, for the reason BackupCoverageTest gives: what is
// under test is a resource, not the start-up sequence.
@Config(sdk = [34], application = android.app.Application::class)
class AutoBackupRulesTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        // Drop every cached singleton first.
        //
        // Room keeps its database in a static field and Robolectric hands each *test method* its
        // own data directory, so the instance built during the first method points at a directory
        // the second one cannot see. `getInstance` then cheerfully returns it, no database is
        // created where this test is looking, and the census comes back empty — which is how this
        // test failed on a set of rules that were perfectly correct.
        closeEveryDatabase()

        // Every app's database, created the way the app itself creates it.
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
    }

    /** The same eleven `closeInstance` calls `BackupCoverageTest` makes before a restore. */
    private fun closeEveryDatabase() {
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
    }

    @Test
    fun `every database the suite creates is carried by the platform's backup`() {
        val databases = databasesOnDisk()
        // A census of nothing would pass every assertion below, so an empty one is a failure rather
        // than a pass: it means the seeding stopped working, or the suite is being measured in the
        // wrong place. The directory is named because that is the question a reader will have.
        assertTrue(
            "no app created a database in ${databasesDir()} — the seeding above has stopped " +
                "working, and a census of nothing proves nothing",
            databases.size >= 11
        )

        allSections().forEach { (section, rules) ->
            val dropped = databases.filterNot { rules.carries(DATABASE, it) }
            assertTrue(
                "$section would leave these databases on the old phone: $dropped — either add them " +
                    "to the rules or say in the file why a restore should come up without them",
                dropped.isEmpty()
            )
        }
    }

    @Test
    fun `no credential store is handed to the platform's backup`() {
        allSections().forEach { (section, rules) ->
            CREDENTIAL_STORES.forEach { name ->
                assertTrue(
                    "$section would back up $name. Every credential store in the suite stays on the " +
                        "phone: the Keystore key that opens it never travels, so the restored copy " +
                        "is unreadable — and the vault is what carries these onto a new phone.",
                    !rules.carries(SHAREDPREF, "$name.xml")
                )
            }
        }
    }

    @Test
    fun `preferences are still carried, so the exclusions are exclusions and not an empty domain`() {
        allSections().forEach { (section, rules) ->
            assertTrue("$section stopped backing up preferences", rules.carries(SHAREDPREF, "lifeops_prefs.xml"))
        }
    }

    @Test
    fun `the cloud-backup and device-transfer rules say exactly what the older rules say`() {
        val sections = allSections()
        assertEquals("three sections are expected: the API 31+ pair and the pre-31 file", 3, sections.size)
        val distinct = sections.values.distinct()
        assertEquals(
            "these rules are one policy in three places and they have drifted apart: $sections",
            1,
            distinct.size
        )
    }

    // -----------------------------------------------------------------------------------------
    // The rules, as the platform reads them
    // -----------------------------------------------------------------------------------------

    /** Every rule section the app ships, keyed by where it came from. */
    private fun allSections(): Map<String, List<BackupRule>> =
        parse(R.xml.backup_rules) + parse(R.xml.data_extraction_rules)

    /**
     * Read one rules resource into its sections.
     *
     * The section is whatever element the `<include>`/`<exclude>` lines sit inside:
     * `full-backup-content` in the older file, `cloud-backup` and `device-transfer` in the newer
     * one. Parsing the compiled resource rather than the file on disk is deliberate — what ships is
     * what the packaging step produced, and that is the thing worth asserting about.
     */
    private fun parse(resId: Int): Map<String, List<BackupRule>> {
        val sections = linkedMapOf<String, MutableList<BackupRule>>()
        val parser = context.resources.getXml(resId)
        try {
            var section = ""
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    val included = parser.name == "include"
                    if (included || parser.name == "exclude") {
                        sections.getOrPut(section) { mutableListOf() } += BackupRule(
                            included = included,
                            // No namespace: both rule formats spell their attributes plainly,
                            // unlike almost every other XML resource in an Android app. A reader
                            // who assumes `android:domain` here gets null and an empty rule set.
                            domain = parser.getAttributeValue(null, "domain").orEmpty(),
                            path = parser.getAttributeValue(null, "path").orEmpty()
                        )
                    } else {
                        section = parser.name
                    }
                }
                event = parser.next()
            }
        } finally {
            parser.close()
        }
        return sections
    }

    /**
     * Where this app's databases live, asked of the framework rather than assembled from
     * `applicationInfo.dataDir`: `getDatabasePath` is the call Room itself makes to decide where to
     * put a database, so it is the one answer that cannot disagree with reality. The assembled path
     * did disagree — under Robolectric it names a directory nothing is written to, and the census
     * came back empty while every app had in fact created its database.
     */
    private fun databasesDir(): File =
        context.getDatabasePath("census-probe.db").parentFile
            ?: error("the platform gave a database path with no directory above it")

    private fun databasesOnDisk(): List<String> =
        databasesDir().listFiles().orEmpty()
            .filter { it.isFile }
            // A journal beside a database is not a file anybody backs up; it is checkpointed into
            // the database before the copy, which is what makes the copy whole.
            .filterNot { it.name.endsWith("-journal") || it.name.endsWith("-wal") || it.name.endsWith("-shm") }
            .map { it.name }
            .sorted()

    /** One `<include>` or `<exclude>` line. */
    private data class BackupRule(val included: Boolean, val domain: String, val path: String)

    /**
     * Does this section back up `relPath` in `domain`?
     *
     * The platform's own resolution: a path is backed up when some `<include>` matches it and no
     * more-specific `<exclude>` does, where specificity is how much of the path a rule spells out.
     * A tie goes to the exclusion — "don't" beats "do" at the same depth — and a domain with no
     * matching include at all is not backed up, which is what makes `filesDir` absent from these
     * files an answer rather than an omission.
     */
    private fun List<BackupRule>.carries(domain: String, relPath: String): Boolean {
        var included = -1
        var excluded = -1
        forEach { rule ->
            if (rule.domain != domain) return@forEach
            // `path="."` is how the whole domain is spelled; it matches everything in it.
            val prefix = rule.path.trim().let { if (it == "." || it == "./") "" else it.trim('/') }
            val matches = prefix.isEmpty() || relPath == prefix || relPath.startsWith("$prefix/")
            if (!matches) return@forEach
            if (rule.included) included = maxOf(included, prefix.length)
            else excluded = maxOf(excluded, prefix.length)
        }
        return included > excluded
    }

    private companion object {

        const val DATABASE = "database"
        const val SHAREDPREF = "sharedpref"

        /**
         * Every preferences file in the suite that holds a credential.
         *
         * The container's two are read from the classes that own them, so a rename cannot leave
         * this list pointing at a file that no longer exists. The hosted ones are spelled out,
         * for the same reason `BackupCoverageTest` spells them out: each is a `private const` inside
         * its app, kept private on purpose (the name is deliberately outside its app's backup
         * prefix, which is the mechanism keeping it out of the sandbox's archive), and opening them
         * up to be read from here would weaken the thing being protected to test it.
         */
        val CREDENTIAL_STORES = listOf(
            // Citation: the O'Reilly card and PIN, and the OPDS catalogue sign-ins.
            "oreilly_access",
            "opds_catalog_access",
            // Finance: the bank and aggregator tokens.
            "secure_finance_access",
            // Secrets: the vault key wrapped by this phone's Keystore.
            "secure_secrets_device",
            // Utilities: the key the sealed-message store is encrypted with, and its unencrypted
            // fallback — the one file in this list that is a working key in plain text.
            "secure_utilities_seal_key",
            "secure_utilities_seal_key_plain",
            // The container's own, and their unencrypted fallbacks.
            UpdatePrefs.SECRETS_PREFS,
            UpdatePrefs.SECRETS_PREFS_PLAIN,
            CloudBackupPrefs.SECRETS_PREFS,
            CloudBackupPrefs.SECRETS_PREFS_PLAIN
        )
    }
}
