package com.advisor.app.data.prompt

import android.content.Context
import com.advisor.app.logic.PromptAssembler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Persists the Advisor's **system prompt** — the standing instruction that opens every prompt the
 * model sees — as a plain-text file at `filesDir/advisor/system-prompt.txt`.
 *
 * Plain text, not JSON and not the database, for the same reasons as
 * [com.advisor.app.data.identity.IdentityStore]: it is prose, it is slow-changing, and it is worth
 * reading and hand-editing. It is also the one piece of the prompt the user can meaningfully tune —
 * tone, what to lead with, what never to do — so it belongs somewhere they can see and revert.
 *
 * There is no file until the user actually customises it. An absent or blank file means "use
 * [PromptAssembler.SYSTEM]", so the shipped default keeps improving with the app instead of being
 * frozen into a copy on first launch, and [reset] is just a delete.
 *
 * Reads are memoised because [load] is on the per-question path; writes drop the memo. Failures are
 * soft — an unreadable file yields the default rather than breaking every answer.
 */
class SystemPromptStore(context: Context) {

    private val appContext = context.applicationContext

    @Volatile private var cached: String? = null

    /** The prompt to use: the user's text if they have set one, else the shipped default. */
    suspend fun load(): String = cached ?: withContext(Dispatchers.IO) {
        val text = runCatching { file(appContext).takeIf { it.exists() }?.readText() }
            .getOrNull()
            ?.trim()
            .orEmpty()
        val resolved = text.ifBlank { DEFAULT }
        cached = resolved
        resolved
    }

    /** True when the user has replaced the default — drives the "reset" affordance in the UI. */
    suspend fun isCustom(): Boolean = load() != DEFAULT

    /**
     * Save [text] as the system prompt. Blank input is a [reset] rather than an empty prompt: a model
     * with no standing instruction answers unusably, and an empty text box should not be able to
     * produce that state by accident.
     */
    suspend fun save(text: String) {
        val trimmed = text.trim()
        if (trimmed.isBlank() || trimmed == DEFAULT) return reset()
        withContext(Dispatchers.IO) {
            val target = file(appContext)
            target.parentFile?.mkdirs()
            target.writeText(trimmed)
        }
        cached = trimmed
    }

    /** Drop any customisation and go back to the shipped default. */
    suspend fun reset() {
        withContext(Dispatchers.IO) { runCatching { file(appContext).delete() } }
        cached = DEFAULT
    }

    companion object {
        /** The shipped instruction, used whenever the user has not written their own. */
        val DEFAULT: String get() = PromptAssembler.SYSTEM

        /** The file's canonical location; the backup contributor reads the same path. */
        fun file(context: Context): File =
            File(File(context.filesDir, "advisor"), "system-prompt.txt")
    }
}
