package com.lifeops.app.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.lifeops.app.data.db.LifeOpsDatabase
import com.lifeops.app.data.db.entities.*
import com.lifeops.app.data.model.CustomPalette
import com.lifeops.app.util.Csv
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private data class BackupData(
    val version: Int = 10,
    val aspects: List<AspectEntity>,
    val categories: List<CategoryEntity>,
    val weeks: List<WeekEntity>,
    val tasks: List<TaskEntity>,
    val taskNotes: List<TaskNoteEntity>,
    val timeEntries: List<TimeEntryEntity>,
    val weekSnapshots: List<WeekSnapshotEntity>,
    val costResources: List<CostResourceEntity> = emptyList(),
    val taskCostEntries: List<TaskCostEntryEntity> = emptyList(),
    val projects: List<ProjectEntity> = emptyList(),
    // Collection hub (v6): books, recipes, and future project ideas. Food items ride along
    // because recipe ingredients hold a foreign key into them.
    val books: List<BookEntity> = emptyList(),
    val bookNotes: List<BookNoteEntity> = emptyList(),
    val bookTimeEntries: List<BookTimeEntryEntity> = emptyList(),
    val foodItems: List<FoodItemEntity> = emptyList(),
    val recipes: List<RecipeEntity> = emptyList(),
    val recipeIngredients: List<RecipeIngredientEntity> = emptyList(),
    val futureProjects: List<FutureProjectEntity> = emptyList(),
    // v7: future project notes replace the long-form content blob on future_projects.
    val futureProjectNotes: List<FutureProjectNoteEntity> = emptyList(),
    // v8: household people, their notes, and task-involvement links.
    val persons: List<PersonEntity> = emptyList(),
    val personNotes: List<PersonNoteEntity> = emptyList(),
    val taskPeople: List<TaskPersonEntity> = emptyList(),
    // v9: per-task weather requirements.
    val taskWeatherRequirements: List<TaskWeatherRequirementEntity> = emptyList(),
    // v10: saved activity templates (built-in + custom).
    val activityTemplates: List<ActivityTemplateEntity> = emptyList(),
    val customPalette: CustomPalette? = null
)

class BackupRepository(private val db: LifeOpsDatabase) {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    suspend fun buildBackupJson(customPalette: CustomPalette? = null): String = withContext(Dispatchers.IO) {
        val data = BackupData(
            aspects = db.aspectDao().getAllSync(),
            categories = db.categoryDao().getAllSync(),
            weeks = db.weekDao().getAllSync(),
            tasks = db.taskDao().getAll(),
            taskNotes = db.taskNoteDao().getAll(),
            timeEntries = db.timeEntryDao().getAll(),
            weekSnapshots = db.weekSnapshotDao().getAll(),
            costResources = db.costResourceDao().getAllSync(),
            taskCostEntries = db.taskCostEntryDao().getAll(),
            projects = db.projectDao().getAll(),
            books = db.bookDao().getAll(),
            bookNotes = db.bookDao().getAllNotes(),
            bookTimeEntries = db.bookDao().getAllTimeEntries(),
            foodItems = db.foodItemDao().getAll(),
            recipes = db.recipeDao().getAll(),
            recipeIngredients = db.recipeDao().getAllIngredients(),
            futureProjects = db.futureProjectDao().getAll(),
            futureProjectNotes = db.futureProjectDao().getAllNotes(),
            persons = db.personDao().getAll(),
            personNotes = db.personDao().getAllNotes(),
            taskPeople = db.personDao().getAllLinks(),
            taskWeatherRequirements = db.weatherDao().getAllRequirements(),
            activityTemplates = db.activityTemplateDao().getAll(),
            customPalette = customPalette
        )
        gson.toJson(data)
    }

    fun extractCustomPalette(json: String): CustomPalette? = try {
        gson.fromJson(json, BackupData::class.java)?.customPalette
    } catch (_: Exception) { null }

    suspend fun saveBackupFile(context: Context, json: String): Uri? = withContext(Dispatchers.IO) {
        try {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: return@withContext null
            dir.mkdirs()
            val file = File(dir, "lifeops_backup.json")
            file.writeText(json)
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun readFromUri(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
        } catch (e: Exception) {
            null
        }
    }

    fun parseVersion(json: String): Int = try {
        JsonParser.parseString(json).asJsonObject.get("version")?.asInt ?: 1
    } catch (_: Exception) { 1 }

    suspend fun restore(json: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val data = gson.fromJson(json, BackupData::class.java)
                ?: return@withContext Result.failure(IllegalArgumentException("Invalid backup JSON"))
            db.withTransaction {
                for (a in data.aspects) db.aspectDao().upsert(a)
                for (c in data.categories) db.categoryDao().upsert(c)
                for (w in data.weeks) db.weekDao().upsert(w)
                for (t in data.tasks) db.taskDao().upsert(t)
                for (n in data.taskNotes) db.taskNoteDao().insert(n)
                for (e in data.timeEntries) db.timeEntryDao().insert(e)
                for (s in data.weekSnapshots) {
                    // Backups written before v5 have no aspectHistory; Gson leaves it null,
                    // which would violate the NOT NULL column. Coerce to "{}".
                    val rawHistory: String? = s.aspectHistory
                    db.weekSnapshotDao().insert(
                        if (rawHistory == null) s.copy(aspectHistory = "{}") else s
                    )
                }
                for (r in data.costResources) db.costResourceDao().upsert(r)
                for (ce in data.taskCostEntries) db.taskCostEntryDao().insert(ce)
                for (p in data.projects) db.projectDao().upsert(p)
                for (b in data.books) db.bookDao().upsert(b)
                for (bn in data.bookNotes) db.bookDao().insertNote(bn)
                for (bt in data.bookTimeEntries) db.bookDao().insertTimeEntry(bt)
                // Food items before recipe ingredients: ingredients reference them by FK.
                for (fi in data.foodItems) db.foodItemDao().upsert(fi)
                for (rc in data.recipes) db.recipeDao().upsert(rc)
                for (ri in data.recipeIngredients) db.recipeDao().upsertIngredient(ri)
                for (fp in data.futureProjects) {
                    // Backups written before the archive lifecycle carry no status; Gson leaves
                    // it null despite the Kotlin default, which would violate NOT NULL.
                    val rawStatus: String? = fp.status
                    db.futureProjectDao().upsert(fp.copy(content = "", status = rawStatus ?: "active"))
                }
                for (fpn in data.futureProjectNotes) db.futureProjectDao().insertNote(fpn)
                // People before their notes/links (FK), and after tasks (task_people → tasks).
                for (person in data.persons) db.personDao().upsertPerson(person)
                for (pn in data.personNotes) db.personDao().insertNote(pn)
                for (link in data.taskPeople) db.personDao().attach(link)
                // Task weather requirements: after tasks (FK taskId → tasks).
                for (req in data.taskWeatherRequirements) db.weatherDao().upsertRequirement(req)
                // Saved activities (standalone, no FKs).
                for (at in data.activityTemplates) db.activityTemplateDao().upsert(at)
                // Backups written before v7 carried one long-form content blob per future
                // project; fold it into a single catch-up note. The deterministic '-catchup'
                // id matches MIGRATION_25_26, so restoring the same backup twice (or restoring
                // onto an already-migrated project) can't duplicate the note.
                for (fp in data.futureProjects) {
                    if (fp.content.isNotBlank()) {
                        db.futureProjectDao().insertNote(
                            FutureProjectNoteEntity("${fp.id}-catchup", fp.id, fp.content, fp.updatedAt)
                        )
                    }
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun buildCsvExport(): String = withContext(Dispatchers.IO) {
        val tasks = db.taskDao().getAll()
        val aspects = db.aspectDao().getAllSync().associateBy { it.id }
        val categories = db.categoryDao().getAllSync().associateBy { it.id }
        val weeks = db.weekDao().getAllSync().associateBy { it.id }
        val projects = db.projectDao().getAll().associateBy { it.id }
        val timeByTask = db.timeEntryDao().getAll()
            .groupBy { it.taskId }
            .mapValues { (_, entries) -> entries.sumOf { it.durationMinutes } }
        val notesByTask = db.taskNoteDao().getAll()
            .groupBy { it.taskId }
            .mapValues { (_, notes) -> notes.joinToString("; ") { it.content } }
        val costResources = db.costResourceDao().getAllSync().associateBy { it.id }
        val costByTask = db.taskCostEntryDao().getAll()
            .groupBy { it.taskId }
            .mapValues { (_, entries) ->
                entries.joinToString("; ") { e -> "${costResources[e.resourceId]?.name ?: e.resourceId}:${e.amount}" }
            }

        // One row per task carrying every task-level field. Proper CSV quoting (via Csv) means
        // commas in titles/notes are preserved rather than mangled, and timestamps are kept in
        // full. Still a flat projection: the JSON backup remains the lossless / restorable copy.
        val sb = StringBuilder()
        sb.append(Csv.row(listOf(
            "ID", "Title", "Week", "Aspect", "Category", "Project", "Priority", "Status", "Source",
            "DueDate", "HardDeadline", "Recurring", "EstimatedMinutes", "TimeLoggedMinutes",
            "ResourceValue", "CarriedCount", "CostEntries", "Notes", "CreatedAt", "CompletedAt"
        ))).append('\n')
        tasks.forEach { t ->
            sb.append(Csv.row(listOf(
                t.id,
                t.title,
                weeks[t.weekId]?.startDate ?: "",
                aspects[t.aspectId]?.name ?: "",
                categories[t.categoryId]?.name ?: "",
                projects[t.projectId]?.title ?: "",
                t.priority,
                t.status,
                t.source,
                t.dueDate ?: "",
                if (t.hardDeadline) "true" else "false",
                if (t.isRecurring) "true" else "false",
                t.estimatedMinutes?.toString() ?: "",
                (timeByTask[t.id] ?: 0).toString(),
                t.resourceValue.toString(),
                t.carriedCount.toString(),
                costByTask[t.id] ?: "",
                notesByTask[t.id] ?: "",
                t.createdAt,
                t.completedAt ?: ""
            ))).append('\n')
        }
        sb.toString()
    }

    fun shareBackupFile(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, "LifeOps Backup")
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Save Backup").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    fun shareText(context: Context, content: String, subject: String, mimeType: String = "text/plain") {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, content)
        }
        context.startActivity(Intent.createChooser(intent, subject).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }
}
