package com.utilities.app.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.utilities.app.keyboard.logic.KeyboardLook
import com.utilities.app.messages.logic.ChatLook
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Where the two looks live, and the one piece of state both takeovers read.
 *
 * ## Why a flow and not a preferences read
 *
 * The keyboard is not an activity. It is a service the operating system binds, whose window is put
 * up over *other people's apps*, and which is very often already running when somebody changes a
 * colour on the settings screen. A store that was read once at `onCreateInputView` would leave the
 * household adjusting a slider and watching nothing happen until they closed and reopened every app
 * on the phone. So the look is a [StateFlow] the service collects, and a colour change repaints the
 * keys under whatever is on screen.
 *
 * ## Why JSON in a preferences file
 *
 * Because the looks are nested values with a dozen nullable colour fields each, and the alternative
 * — a preference key per field — is thirty keys that have to be kept in step with the data classes
 * by hand, which is exactly the kind of drift that loses somebody's settings on an upgrade. Gson is
 * already in the suite's catalogue and is pure JVM. A document that cannot be parsed falls back to
 * the default rather than throwing: a keyboard that will not come up because a colour was stored
 * wrong is not a keyboard.
 *
 * The file is named so the backup contributor carries it — see `UtilitiesBackupContributor` — and
 * there is nothing in it but appearance and a handful of switches.
 *
 * ## The merge, which is not decoration
 *
 * A stored document is merged **over the defaults** rather than deserialized on its own. Gson
 * builds objects without calling their constructors, so a field added to one of these classes after
 * somebody's settings were written comes back as `false` or `0` or `null` — which for
 * `heightScale` is a keyboard with no height, and for a nested value a crash on first read. Merging
 * over a tree of the defaults means an old document supplies exactly what it knows about and every
 * field added since arrives at the value the data class declares.
 */
class LookStore private constructor(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    private val _keyboard = MutableStateFlow(readKeyboard())
    val keyboard: StateFlow<KeyboardLook> = _keyboard.asStateFlow()

    private val _chat = MutableStateFlow(readChat())
    val chat: StateFlow<ChatLook> = _chat.asStateFlow()

    fun setKeyboard(look: KeyboardLook) {
        val clean = look.sanitized()
        _keyboard.value = clean
        write(KEY_KEYBOARD, clean)
    }

    fun updateKeyboard(transform: (KeyboardLook) -> KeyboardLook) = setKeyboard(transform(_keyboard.value))

    fun setChat(look: ChatLook) {
        val clean = look.sanitized()
        _chat.value = clean
        write(KEY_CHAT, clean)
    }

    fun updateChat(transform: (ChatLook) -> ChatLook) = setChat(transform(_chat.value))

    /**
     * Take the keyboard's surface settings for the threads, or the other way round.
     *
     * The one button on both settings screens that people actually press. Only the shared
     * [com.utilities.app.look.UtilityLook] travels — a bubble's corner radius is not a key's.
     */
    fun matchChatToKeyboard() = setChat(_chat.value.copy(look = _keyboard.value.look))

    fun matchKeyboardToChat() = setKeyboard(_keyboard.value.copy(look = _chat.value.look))

    /** Re-read from disk. For the restore, which writes the file underneath a running process. */
    fun reload() {
        _keyboard.value = readKeyboard()
        _chat.value = readChat()
    }

    private fun readKeyboard(): KeyboardLook =
        read(KEY_KEYBOARD, KeyboardLook(), KeyboardLook::class.java).sanitized()

    private fun readChat(): ChatLook =
        read(KEY_CHAT, ChatLook(), ChatLook::class.java).sanitized()

    /**
     * The stored document merged over [default]'s own tree. See the class note for why the merge is
     * the whole of it. Anything that throws on the way — malformed JSON, an enum this build does not
     * have, a nested object that came back null — gives way to the default rather than to a crash
     * in a service the household cannot uninstall their way out of.
     */
    private fun <T : Any> read(key: String, default: T, type: Class<T>): T {
        val raw = prefs.getString(key, null) ?: return default
        return runCatching {
            val stored = JsonParser.parseString(raw)
            if (!stored.isJsonObject) return default
            val merged = gson.toJsonTree(default).asJsonObject
            overlay(merged, stored.asJsonObject)
            gson.fromJson(merged, type) ?: default
        }.getOrDefault(default)
    }

    /** [from]'s values win, field by field, all the way down. */
    private fun overlay(into: JsonObject, from: JsonObject) {
        from.entrySet().forEach { (key, value) ->
            val existing = into.get(key)
            if (existing != null && existing.isJsonObject && value.isJsonObject) {
                overlay(existing.asJsonObject, value.asJsonObject)
            } else {
                into.add(key, value)
            }
        }
    }

    private fun write(key: String, value: Any) {
        prefs.edit().putString(key, gson.toJson(value)).apply()
    }

    companion object {

        /** Carried by the archive. Nothing in it is a secret; all of it is a preference. */
        const val FILE_NAME = "utilities_look"

        private const val KEY_KEYBOARD = "keyboard"
        private const val KEY_CHAT = "chat"

        @Volatile
        private var instance: LookStore? = null

        fun get(context: Context): LookStore =
            instance ?: synchronized(this) {
                instance ?: LookStore(context).also { instance = it }
            }
    }
}
