package com.utilities.app.data

import android.content.Context
import com.utilities.app.keyboard.logic.Lexicon
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * The learned words, on disk.
 *
 * One plain text file, readable with `cat`, in this app's own directory. That is a deliberate
 * choice rather than laziness: the promise the keyboard makes is that it keeps a word list and
 * nothing else, and a promise nobody can check is a promise. Anybody who wants to know what their
 * keyboard has on them can look at the file, and the settings screen lists the same thing.
 *
 * Writes are debounced and off the main thread. The keyboard learns a word every time somebody
 * types a space, which is several times a second in a message — writing four thousand lines on each
 * one would put a file write in the middle of every keystroke. A word is added to the in-memory
 * list immediately (so it can be suggested immediately) and the file catches up.
 *
 * The write is atomic: a temporary file, then a rename. A phone that dies mid-write otherwise comes
 * back with a truncated list, and a truncated list of the *most typed words* is exactly the data
 * whose loss is most annoying.
 */
class LexiconStore private constructor(context: Context) {

    private val file = File(File(context.applicationContext.filesDir, DIR_NAME).apply { mkdirs() }, FILE_NAME)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()

    private val _lexicon = MutableStateFlow(load())
    val lexicon: StateFlow<Lexicon> = _lexicon.asStateFlow()

    /** Typed one word. Cheap: the list is replaced in memory and the file write is queued. */
    fun learn(word: String) {
        val next = _lexicon.value.learn(word)
        if (next === _lexicon.value) return
        _lexicon.value = next
        persist()
    }

    fun forget(word: String) {
        val next = _lexicon.value.forget(word)
        if (next === _lexicon.value) return
        _lexicon.value = next
        persist()
    }

    /**
     * Throw the whole list away.
     *
     * Called when somebody turns learning off, as well as from the button that says so. A switch
     * that stopped the list growing and left what was already in it would be a switch that does
     * half of what its label says.
     */
    fun clear() {
        _lexicon.value = Lexicon.EMPTY
        persist()
    }

    /** Re-read the file — after a restore has written it underneath a running keyboard. */
    fun reload() {
        _lexicon.value = load()
    }

    /** Where the archive finds it. */
    fun fileOnDisk(): File = file

    private fun load(): Lexicon =
        runCatching { if (file.exists()) Lexicon.parse(file.readText()) else Lexicon.EMPTY }
            .getOrDefault(Lexicon.EMPTY)

    private fun persist() {
        val snapshot = _lexicon.value
        scope.launch {
            writeLock.withLock {
                runCatching {
                    val temp = File(file.parentFile, "$FILE_NAME.tmp")
                    temp.writeText(snapshot.serialize())
                    if (!temp.renameTo(file)) {
                        file.writeText(snapshot.serialize())
                        temp.delete()
                    }
                }
            }
        }
    }

    companion object {

        /** Under `filesDir`, so the backup contributor can sweep the directory whole. */
        const val DIR_NAME = "utilities"
        const val FILE_NAME = "lexicon.txt"

        @Volatile
        private var instance: LexiconStore? = null

        fun get(context: Context): LexiconStore =
            instance ?: synchronized(this) {
                instance ?: LexiconStore(context).also { instance = it }
            }

        /** The instance if one exists — for the restore, which must not create a store to notify it. */
        fun peek(): LexiconStore? = instance
    }
}
