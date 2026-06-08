package com.lifeops.app.data.repository

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.lifeops.app.data.db.LifeOpsDatabase
import com.lifeops.app.data.db.entities.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private data class BackupData(
    val version: Int = 1,
    val aspects: List<AspectEntity>,
    val categories: List<CategoryEntity>,
    val weeks: List<WeekEntity>,
    val tasks: List<TaskEntity>,
    val taskNotes: List<TaskNoteEntity>,
    val timeEntries: List<TimeEntryEntity>,
    val weekSnapshots: List<WeekSnapshotEntity>
)

class BackupRepository(private val db: LifeOpsDatabase) {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    suspend fun buildBackupJson(): String = withContext(Dispatchers.IO) {
        val data = BackupData(
            aspects = db.aspectDao().getAllSync(),
            categories = db.categoryDao().getAllSync(),
            weeks = db.weekDao().getAllSync(),
            tasks = db.taskDao().getAll(),
            taskNotes = db.taskNoteDao().getAll(),
            timeEntries = db.timeEntryDao().getAll(),
            weekSnapshots = db.weekSnapshotDao().getAll()
        )
        gson.toJson(data)
    }

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
                for (s in data.weekSnapshots) db.weekSnapshotDao().insert(s)
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
        val timeByTask = db.timeEntryDao().getAll()
            .groupBy { it.taskId }
            .mapValues { (_, entries) -> entries.sumOf { it.durationMinutes } }
        val notesByTask = db.taskNoteDao().getAll()
            .groupBy { it.taskId }
            .mapValues { (_, notes) -> notes.joinToString("; ") { it.content } }

        val sb = StringBuilder()
        sb.appendLine("Title,Aspect,Category,Priority,Status,DueDate,HardDeadline,Recurring,EstimatedMinutes,TimeLoggedMinutes,CarriedCount,Notes,CreatedAt,CompletedAt")
        tasks.forEach { t ->
            val aspect = aspects[t.aspectId]?.name ?: ""
            val category = categories[t.categoryId]?.name ?: ""
            val notes = notesByTask[t.id]?.replace(",", ";") ?: ""
            sb.appendLine(
                listOf(
                    t.title.replace(",", ";"),
                    aspect,
                    category,
                    t.priority,
                    t.status,
                    t.dueDate ?: "",
                    if (t.hardDeadline) "true" else "false",
                    if (t.isRecurring) "true" else "false",
                    t.estimatedMinutes?.toString() ?: "",
                    timeByTask[t.id]?.toString() ?: "0",
                    t.carriedCount.toString(),
                    notes,
                    t.createdAt.take(10),
                    t.completedAt?.take(10) ?: ""
                ).joinToString(",")
            )
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
