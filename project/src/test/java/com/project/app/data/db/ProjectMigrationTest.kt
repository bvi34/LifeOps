package com.project.app.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.project.app.data.db.entities.DocRevisionBlockEntity
import com.project.app.data.db.entities.DocRevisionEntity
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * The upgrade path: every database this app has ever written, opened by the app as it is today.
 *
 * This is the test the whole backup story rests on. What Project holds may be the only copy of the
 * writing in it — there is no cloud workspace keeping a second one — and the way that gets lost is
 * not a dramatic bug. It is a column added to an entity with no matching migration, which nobody
 * notices until a phone that has had the app since the first release refuses to open it. Room does
 * not fall back destructively here (see [ProjectDatabase.builder]) precisely so that failure is
 * loud, and this is where it is supposed to be heard.
 *
 * The assertion is Room's own. A database with no `room_master_table` sends Room down its
 * schema-validation path on open: it reads back every table, column, primary key, index and foreign
 * key it finds and compares them against what the entities describe, then throws with the
 * difference if they disagree. So each test below writes a real file at some past version, opens it
 * through the production builder, and lets Room be the judge.
 *
 * The DDL is **read from the exported schemas** rather than copied into this file, so the database
 * that gets opened is the one that shipped, and the day a version 2 is added its upgrade is covered
 * without anybody remembering to come back here.
 *
 * That alone would be a test agreeing with itself, though, and the way it would happen is worth
 * spelling out because it is the ordinary case rather than an exotic one. Change an entity without
 * touching [PROJECT_DB_VERSION] and Room does not complain — it quietly **rewrites** the exported
 * schema for the version already in `project/schemas/`. Both halves then move together: the test
 * builds a database from the rewritten DDL, opens it, and agrees that today's entities describe it.
 * Meanwhile the phone that has had the app since release still holds the *old* shape, and Room will
 * refuse to open it.
 *
 * [SCHEMA_FINGERPRINTS] is the fixed point that stops that. Each entry is the identity hash a
 * version was released with — a value that lives here, in the test, where regenerating a schema
 * cannot reach it. A rewritten schema no longer matches its fingerprint and this file fails, naming
 * the version that moved.
 */
@RunWith(RobolectricTestRunner::class)
class ProjectMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dbName = "migration-test.db"

    @After
    fun tearDown() {
        SQLiteDatabase.deleteDatabase(context.getDatabasePath(dbName))
    }

    @Test
    fun `every schema version this app has ever exported still opens today`() {
        val versions = exportedVersions()
        assertTrue("no exported schemas found in $schemaDir", versions.isNotEmpty())

        versions.forEach { version ->
            writeDatabaseAt(version)
            // Opening is the assertion: Room validates the schema it finds against the schema its
            // entities describe, and throws if a migration is missing or wrong. Nothing else here
            // has to know what changed between two versions.
            openThroughProduction().close()
            SQLiteDatabase.deleteDatabase(context.getDatabasePath(dbName))
        }
    }

    @Test
    fun `the version the code declares is one the schemas were exported for`() {
        // Bumping PROJECT_DB_VERSION without committing the schema KSP wrote for it would leave the
        // test above silently testing one version fewer than the app can produce.
        assertTrue(
            "schema $PROJECT_DB_VERSION.json was never exported — run the build and commit it",
            PROJECT_DB_VERSION in exportedVersions()
        )
    }

    @Test
    fun `no version's schema has been rewritten since it shipped`() {
        exportedVersions().forEach { version ->
            val fingerprint = SCHEMA_FINGERPRINTS[version]
            assertNotNull(
                "version $version has no fingerprint. If you have just added it, record its " +
                    "identityHash from $version.json in SCHEMA_FINGERPRINTS.",
                fingerprint
            )
            assertEquals(
                "The schema for version $version has changed since it shipped, and the version was " +
                    "not bumped. Every phone already holding a version $version database will fail " +
                    "to open this build. Restore $version.json, raise PROJECT_DB_VERSION, and write " +
                    "the migration.",
                fingerprint,
                identityHashOf(version)
            )
        }
    }

    @Test
    fun `the schema the code builds today is the one the current version claims`() {
        writeDatabaseAt(PROJECT_DB_VERSION)

        // Validating a database with no `room_master_table` makes Room write its own identity hash
        // into one, which is the only public sight of the value the *entities* hash to. Comparing it
        // against the fingerprint ties that value to the code rather than to a file that is
        // regenerated alongside the code it describes.
        openThroughProduction().close()

        assertEquals(
            "today's entities do not hash to the fingerprint recorded for version $PROJECT_DB_VERSION",
            SCHEMA_FINGERPRINTS[PROJECT_DB_VERSION],
            storedIdentityHash()
        )
    }

    @Test
    fun `a database written at version 1 keeps its rows, its tree and its links`() = runTest {
        writeDatabaseAt(1)
        seedVersionOne()

        val db = openThroughProduction()
        try {
            val dao = db.projectDao()

            // A migration that satisfies Room and drops the data would pass the first test alone,
            // so the rows are read back — one from every table that has anything to say.
            val project = dao.getProject("p1")
            assertEquals("The Kestrel", project?.name)
            assertEquals("writing", project?.kind)

            val outline = dao.getOutline("p1")
            assertEquals(listOf("Chapter one", "The docks"), outline.map { it.title })
            // The tree survives as a tree, not as two rows that used to know about each other.
            assertEquals("n1", outline.first { it.id == "n2" }.parentId)
            assertEquals(420, outline.first { it.id == "n2" }.actualWords)

            val doc = dao.getDoc("d1")
            assertEquals("Scene — the docks", doc?.title)
            // The soft link across sections, which is not a foreign key and so is the one thing a
            // careless migration could quietly null out.
            assertEquals("n2", doc?.outlineNodeId)
            assertEquals(420, doc?.wordCount)

            assertEquals(
                listOf("The docks smelled of tar.", "She did not knock."),
                dao.getBlocks("d1").map { it.text }
            )

            assertEquals("Kestrel", dao.getLore("p1").single().name)
            assertEquals("The coronation", dao.getTimeline("p1").single().title)

            assertEquals(listOf("Drafting", "Done"), dao.getColumns("p1").map { it.name })
            val card = dao.getCard("c1")
            assertEquals("Rewrite the dock scene", card?.title)
            assertEquals("n2", card?.outlineNodeId)
            assertEquals("d1", card?.docId)
            assertNull("a card that was never finished came back done", card?.doneAt)
        } finally {
            db.close()
        }
    }

    @Test
    fun `after the upgrade, a version 1 document can keep and read back a version of itself`() = runTest {
        writeDatabaseAt(1)
        seedVersionOne()

        val db = openThroughProduction()
        try {
            val dao = db.projectDao()

            // A phone that upgrades has no history for the writing it already holds — there is no
            // honest way to invent one — but the first destructive edit after the upgrade has to be
            // covered, and that means the tables MIGRATION_1_2 creates have to really be there,
            // foreign key and all, rather than merely satisfying Room's schema check.
            dao.writeRevision(
                revision = DocRevisionEntity(
                    id = "rev1",
                    docId = "d1",
                    reason = "import",
                    wordCount = 9,
                    savedAt = 1_700_000_000_000L
                ),
                blocks = listOf(
                    DocRevisionBlockEntity("rb1", "rev1", "paragraph", "The docks smelled of tar.", false, 0)
                ),
                pruned = emptyList()
            )

            assertEquals("import", dao.getRevision("rev1")?.reason)
            assertEquals(
                listOf("The docks smelled of tar."),
                dao.getRevisionBlocks("rev1").map { it.text }
            )

            // And the cascade the entity declares is the cascade SQLite enforces: versions belong
            // to a document and go with it.
            dao.deleteDoc("d1")
            assertNull(dao.getRevision("rev1"))
            assertTrue(dao.getRevisionBlocks("rev1").isEmpty())
        } finally {
            db.close()
        }
    }

    // ------------------------------------------------------------------ the schemas, as they shipped

    /** Every version Room has exported a schema for, oldest first. */
    private fun exportedVersions(): List<Int> =
        schemaDir.listFiles { file -> file.extension == "json" }
            .orEmpty()
            .mapNotNull { it.nameWithoutExtension.toIntOrNull() }
            .sorted()

    /**
     * Write an empty database at [version], using that version's own exported DDL.
     *
     * Raw SQLite rather than Room, because Room can only ever create the schema it currently
     * describes — which is the thing being migrated *to*. `room_master_table` is deliberately not
     * written: without it, opening sends Room down the validation path described above, which is a
     * far stricter check than comparing an identity hash.
     */
    private fun writeDatabaseAt(version: Int) {
        val file = context.getDatabasePath(dbName).apply { parentFile?.mkdirs() }
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            ddlFor(version).forEach(db::execSQL)
            db.version = version
        } finally {
            db.close()
        }
    }

    /** The `CREATE` statements for one exported version — tables, their indices, and any views. */
    private fun ddlFor(version: Int): List<String> {
        val schema = JSONObject(File(schemaDir, "$version.json").readText()).getJSONObject("database")
        val statements = mutableListOf<String>()

        val entities = schema.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val entity = entities.getJSONObject(i)
            // Room writes the table's name as a placeholder so the same DDL can be reused for a
            // temporary copy during a migration; here it is always the table itself.
            val table = entity.getString("tableName")
            statements += entity.getString("createSql").withTableName(table)
            val indices = entity.optJSONArray("indices") ?: continue
            for (j in 0 until indices.length()) {
                statements += indices.getJSONObject(j).getString("createSql").withTableName(table)
            }
        }

        val views = schema.optJSONArray("views")
        if (views != null) {
            for (i in 0 until views.length()) {
                val view = views.getJSONObject(i)
                statements += view.getString("createSql").withTableName(view.getString("viewName"))
            }
        }
        return statements
    }

    private fun String.withTableName(name: String) = replace("\${TABLE_NAME}", name)

    /**
     * The production open path — not a rebuilt one that could drift from it.
     *
     * The database is forced open rather than merely built. Room connects lazily: `build()` on its
     * own touches no file and validates no schema, so a build-and-close here would assert precisely
     * nothing while looking exactly like a passing test.
     */
    private fun openThroughProduction(): ProjectDatabase =
        ProjectDatabase.builder(context, dbName).build().also { it.openHelper.writableDatabase }

    /**
     * One project at version 1, with something in every section and both kinds of cross-section
     * link — a document filed under a scene, and a card pointing at both.
     */
    private fun seedVersionOne() {
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(dbName), null)
        try {
            db.execSQL(
                "INSERT INTO projects (id, name, kind, summary, colorArgb, archived, sortOrder, " +
                    "createdAt, updatedAt) VALUES " +
                    "('p1', 'The Kestrel', 'writing', 'A smuggler and a coronation', 4278190080, 0, 0, " +
                    "1600000000000, 1600000000000)"
            )
            db.execSQL(
                "INSERT INTO outline_nodes (id, projectId, parentId, title, synopsis, status, " +
                    "targetWords, actualWords, sortOrder, createdAt, updatedAt) VALUES " +
                    "('n1', 'p1', NULL, 'Chapter one', NULL, 'drafting', 5000, 420, 0, " +
                    "1600000000000, 1600000000000)"
            )
            db.execSQL(
                "INSERT INTO outline_nodes (id, projectId, parentId, title, synopsis, status, " +
                    "targetWords, actualWords, sortOrder, createdAt, updatedAt) VALUES " +
                    "('n2', 'p1', 'n1', 'The docks', 'She arrives.', 'drafted', 1200, 420, 1, " +
                    "1600000000000, 1600000000000)"
            )
            db.execSQL(
                "INSERT INTO docs (id, projectId, parentDocId, title, icon, outlineNodeId, " +
                    "wordCount, sortOrder, createdAt, updatedAt) VALUES " +
                    "('d1', 'p1', NULL, 'Scene — the docks', NULL, 'n2', 420, 0, " +
                    "1600000000000, 1600000000000)"
            )
            db.execSQL(
                "INSERT INTO doc_blocks (id, docId, type, text, checked, sortOrder) VALUES " +
                    "('b1', 'd1', 'paragraph', 'The docks smelled of tar.', 0, 0)"
            )
            db.execSQL(
                "INSERT INTO doc_blocks (id, docId, type, text, checked, sortOrder) VALUES " +
                    "('b2', 'd1', 'paragraph', 'She did not knock.', 0, 1)"
            )
            db.execSQL(
                "INSERT INTO lore_entries (id, projectId, name, category, summary, body, colorArgb, " +
                    "sortOrder, createdAt, updatedAt, aliases) VALUES " +
                    "('l1', 'p1', 'Kestrel', 'character', 'The smuggler', 'Sails the [[Straits]].', " +
                    "4278190080, 0, 1600000000000, 1600000000000, 'the Captain')"
            )
            db.execSQL(
                "INSERT INTO timeline_events (id, projectId, title, detail, era, whenLabel, " +
                    "sortOrder, outlineNodeId, createdAt) VALUES " +
                    "('e1', 'p1', 'The coronation', NULL, 'Before', 'Year 12', 0, 'n2', 1600000000000)"
            )
            db.execSQL(
                "INSERT INTO board_columns (id, projectId, name, sortOrder, wipLimit, isDone) " +
                    "VALUES ('col1', 'p1', 'Drafting', 0, 3, 0)"
            )
            db.execSQL(
                "INSERT INTO board_columns (id, projectId, name, sortOrder, wipLimit, isDone) " +
                    "VALUES ('col2', 'p1', 'Done', 1, NULL, 1)"
            )
            db.execSQL(
                "INSERT INTO board_cards (id, projectId, columnId, title, notes, sortOrder, " +
                    "outlineNodeId, docId, createdAt, doneAt) VALUES " +
                    "('c1', 'p1', 'col1', 'Rewrite the dock scene', NULL, 0, 'n2', 'd1', " +
                    "1600000000000, NULL)"
            )
        } finally {
            db.close()
        }
    }

    /** The identity hash recorded in an exported schema. */
    private fun identityHashOf(version: Int): String =
        JSONObject(File(schemaDir, "$version.json").readText())
            .getJSONObject("database")
            .getString("identityHash")

    /** The identity hash Room wrote into the opened database — the one today's entities hash to. */
    private fun storedIdentityHash(): String? {
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(dbName), null)
        return try {
            db.rawQuery("SELECT identity_hash FROM room_master_table LIMIT 1", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } finally {
            db.close()
        }
    }

    private companion object {

        /**
         * The identity hash each released schema version was built with.
         *
         * This is a **baseline, not a derived value**: it is written down by hand precisely because
         * everything else about a schema is regenerated from the entities, and a check against a
         * regenerated file cannot tell you the entities moved. Add an entry when — and only when —
         * you raise [PROJECT_DB_VERSION] and export a new schema. Editing an existing entry is
         * saying "no database in the world was ever written at the old shape", which after a release
         * is not true.
         */
        val SCHEMA_FINGERPRINTS = mapOf(
            1 to "c062a39ad76bcfb66ae604325fa6da8d",
            // 2 added the two tables that keep versions of a document. Purely additive.
            2 to "d5c1f5fc309f019f2ec00621bec8a41f"
        )
    }

    private val schemaDir: File
        get() = File(
            requireNotNull(System.getProperty("project.schemaDir")) {
                "project.schemaDir is not set — see the testOptions block in project/build.gradle.kts"
            }
        )
}
