package com.advisor.app.data.profile

import android.content.Context
import com.advisor.app.logic.Profile
import com.advisor.app.logic.ProfileDirectives
import com.advisor.app.logic.ProfileEntry
import com.advisor.app.logic.ProfileKind
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Persists the standing [Profile]s as one JSON file each under `filesDir/advisor/profiles/`. Files,
 * not the database — because profiles are the always-on, name-addressable context (like identity),
 * never a per-question query. They're portable, hand-editable, and backed up as their own archive
 * entries.
 *
 * On first use the store seeds two defaults — `user` and `llm-persona` — so the concept is populated
 * out of the box; projects and other profiles are created by the user or appended to by the model
 * (via [append], which creates a profile on demand when the model addresses a new key).
 */
class ProfileStore(context: Context) {

    private val appContext = context.applicationContext
    private val gson = GsonBuilder().setPrettyPrinting().create()

    suspend fun list(): List<Profile> = withContext(Dispatchers.IO) {
        val dir = dir(appContext)
        if (!dir.exists() || dir.listFiles().isNullOrEmpty()) seedDefaults(dir)
        (dir.listFiles { f -> f.extension == "json" } ?: emptyArray())
            .mapNotNull { runCatching { gson.fromJson(it.readText(), Profile::class.java) }.getOrNull() }
            .sortedWith(compareBy({ it.kind.ordinal }, { it.name.lowercase() }))
    }

    suspend fun load(key: String): Profile? = withContext(Dispatchers.IO) {
        val file = fileFor(key)
        if (!file.exists()) null
        else runCatching { gson.fromJson(file.readText(), Profile::class.java) }.getOrNull()
    }

    suspend fun save(profile: Profile) = withContext(Dispatchers.IO) { writeFile(profile) }

    /** Create a profile from a human name; its [Profile.key] is the slug of that name. */
    suspend fun create(name: String, kind: ProfileKind, summary: String = ""): Profile =
        withContext(Dispatchers.IO) {
            val profile = Profile(
                key = ProfileDirectives.slug(name),
                name = name.trim(),
                kind = kind,
                summary = summary.trim(),
                updatedAt = System.currentTimeMillis()
            )
            writeFile(profile)
            profile
        }

    /**
     * Append [text] to the profile addressed by [key], creating it if it doesn't exist yet (so the
     * model can start a new project profile simply by writing to it). Returns the updated profile.
     */
    suspend fun append(key: String, text: String, author: String): Profile = withContext(Dispatchers.IO) {
        val slug = ProfileDirectives.slug(key)
        val now = System.currentTimeMillis()
        val existing = load(slug) ?: Profile(
            key = slug,
            name = humanize(slug),
            kind = ProfileKind.PROJECT,
            updatedAt = now
        )
        val updated = existing.appended(text, author, now)
        writeFile(updated)
        updated
    }

    suspend fun delete(key: String) = withContext(Dispatchers.IO) {
        fileFor(ProfileDirectives.slug(key)).delete()
        Unit
    }

    private fun writeFile(profile: Profile) {
        val file = fileFor(profile.key)
        file.parentFile?.mkdirs()
        file.writeText(gson.toJson(profile))
    }

    private fun fileFor(key: String): File = File(dir(appContext), "${ProfileDirectives.slug(key)}.json")

    private fun seedDefaults(dir: File) {
        dir.mkdirs()
        val now = System.currentTimeMillis()
        listOf(
            Profile(
                key = "user",
                name = "User",
                kind = ProfileKind.USER,
                summary = "Freeform, long-running notes about the user (structured facts live in Identity).",
                updatedAt = now
            ),
            Profile(
                key = "llm-persona",
                name = "LLM Persona",
                kind = ProfileKind.PERSONA,
                summary = "How the assistant should behave — persona, tone, and standing instructions.",
                updatedAt = now
            )
        ).forEach { writeFile(it) }
    }

    private fun humanize(slug: String): String =
        slug.split('-').filter { it.isNotBlank() }.joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    companion object {
        /** The profiles directory; the backup contributor reads/writes the same location. */
        fun dir(context: Context): File =
            File(context.applicationContext.filesDir, "advisor/profiles")
    }
}
