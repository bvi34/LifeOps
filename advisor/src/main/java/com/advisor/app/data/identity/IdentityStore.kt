package com.advisor.app.data.identity

import android.content.Context
import com.advisor.app.logic.Identity
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Persists the user's [Identity] as a small, human-editable **JSON file** at
 * `filesDir/advisor/identity.json` — the source of truth for identity-based data. JSON (not the
 * database) is deliberate: identity is slow-changing, worth reading and hand-editing, and portable;
 * it is backed up as its own archive entry alongside the databases.
 *
 * On first read the file is seeded with the empty schema so the shape is self-documenting and there
 * is always a file to back up and edit. Reads/writes are off the main thread and fail soft — a
 * corrupt or partial file yields [Identity.EMPTY] rather than crashing the screen.
 */
class IdentityStore(context: Context) {

    private val appContext = context.applicationContext
    private val gson = GsonBuilder().setPrettyPrinting().create()

    suspend fun load(): Identity = withContext(Dispatchers.IO) {
        val file = file(appContext)
        if (!file.exists()) {
            writeFile(file, Identity.EMPTY)
            return@withContext Identity.EMPTY
        }
        runCatching { gson.fromJson(file.readText(), Identity::class.java) }
            .getOrNull() ?: Identity.EMPTY
    }

    suspend fun save(identity: Identity) = withContext(Dispatchers.IO) {
        writeFile(file(appContext), identity)
    }

    private fun writeFile(file: File, identity: Identity) {
        file.parentFile?.mkdirs()
        file.writeText(gson.toJson(identity))
    }

    companion object {
        /** The identity JSON's canonical location; the backup contributor reads the same path. */
        fun file(context: Context): File =
            File(context.applicationContext.filesDir, "advisor/identity.json")
    }
}
