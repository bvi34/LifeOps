package com.project.app.logic

/**
 * What a lore entry *is*. The categories are the ones a story or a system actually needs to keep
 * straight; [OTHER] exists so nothing has to be miscategorised to be written down.
 */
enum class LoreCategory(val key: String, val label: String) {
    CHARACTER("character", "Character"),
    PLACE("place", "Place"),
    FACTION("faction", "Faction"),
    ITEM("item", "Item"),
    EVENT("event", "Event"),
    CONCEPT("concept", "Concept"),
    OTHER("other", "Other");

    companion object {
        fun fromKey(key: String?): LoreCategory = entries.firstOrNull { it.key == key } ?: OTHER
    }
}

/** One entry in the project's wiki, framework-free. */
data class LoreEntry(
    val id: String,
    val name: String,
    val category: LoreCategory,
    val summary: String?,
    val body: String,
    /** Other things this is called. "Kestrel", "the Captain", "she" — all one person. */
    val aliases: List<String> = emptyList()
)

/** One `[[link]]` found in a body, and what it turned out to point at. */
data class LoreMention(
    /** The text between the brackets, as written. */
    val target: String,
    /** The entry it resolves to, or null when nothing (or too much) matches. */
    val entryId: String?,
    /** True when more than one entry answers to this name — a link that must not be guessed. */
    val ambiguous: Boolean
) {
    val isBroken: Boolean get() = entryId == null
}

/** Names and aliases, resolved. Built once and reused across a screenful of entries. */
class LoreIndex internal constructor(private val byName: Map<String, List<String>>) {

    /** The single entry [name] refers to, or null when nothing or more than one thing does. */
    fun resolve(name: String): String? = byName[normalize(name)]?.singleOrNull()

    fun isAmbiguous(name: String): Boolean = (byName[normalize(name)]?.size ?: 0) > 1

    fun knows(name: String): Boolean = byName.containsKey(normalize(name))

    companion object {
        internal fun normalize(name: String) = name.trim().lowercase()
    }
}

/**
 * The wiki half of the app: `[[wiki links]]`, what they point at, and what points back.
 *
 * The linking rule is deliberately explicit. Entries are connected because somebody typed
 * `[[Kestrel]]`, never because the word "kestrel" happened to appear in a sentence — automatic
 * entity detection in a fiction wiki produces a graph full of coincidences, and the one link you
 * actually wanted is then indistinguishable from the forty you didn't.
 *
 * The other rule worth naming: **an ambiguous name resolves to nothing.** If two entries answer to
 * "the Captain", the link is reported broken rather than pointed at whichever row the database
 * returned first. A wiki that quietly picks one is a wiki that will be wrong on exactly the entry
 * you were relying on it for, and will never say so.
 */
object Lore {

    private val LINK = Regex("""\[\[([^\[\]]+)]]""")
    private val ALIAS_SEPARATOR = Regex("""\s*[,;]\s*""")

    /** Aliases as stored — one comma-separated column, because they are a list of short strings. */
    fun parseAliases(text: String?): List<String> =
        text?.split(ALIAS_SEPARATOR)?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct().orEmpty()

    fun joinAliases(aliases: List<String>): String = aliases.filter { it.isNotBlank() }.joinToString(", ")

    /**
     * Every `[[link]]` in [text], in the order written, de-duplicated case-insensitively.
     * `[[Name|shown as this]]` keeps the name; the display half is presentation.
     */
    fun links(text: String?): List<String> {
        if (text.isNullOrEmpty()) return emptyList()
        val seen = LinkedHashMap<String, String>()
        LINK.findAll(text).forEach { match ->
            val target = match.groupValues[1].substringBefore('|').trim()
            if (target.isNotEmpty()) seen.putIfAbsent(LoreIndex.normalize(target), target)
        }
        return seen.values.toList()
    }

    /** Build the name/alias index for a project's entries. */
    fun index(entries: List<LoreEntry>): LoreIndex {
        val byName = HashMap<String, MutableList<String>>()
        entries.forEach { entry ->
            (listOf(entry.name) + entry.aliases).forEach { name ->
                if (name.isNotBlank()) {
                    byName.getOrPut(LoreIndex.normalize(name)) { ArrayList() }.let {
                        if (entry.id !in it) it += entry.id
                    }
                }
            }
        }
        return LoreIndex(byName)
    }

    /** The links in [text], each resolved against [index]. */
    fun mentions(text: String?, index: LoreIndex): List<LoreMention> =
        links(text).map { target ->
            LoreMention(
                target = target,
                entryId = index.resolve(target),
                ambiguous = index.isAmbiguous(target)
            )
        }

    /**
     * Who links to whom: entry id → the ids of entries whose bodies point at it.
     *
     * Backlinks are the reason a wiki beats a folder of notes. You write "she trained under
     * [[Kestrel]]" in one entry, and Kestrel's page can then tell you every place she is spoken of
     * without you having remembered to record it there.
     */
    fun backlinks(entries: List<LoreEntry>): Map<String, List<String>> {
        val index = index(entries)
        val out = HashMap<String, MutableList<String>>()
        entries.forEach { entry ->
            mentions(entry.body, index).forEach { mention ->
                val targetId = mention.entryId ?: return@forEach
                if (targetId == entry.id) return@forEach
                out.getOrPut(targetId) { ArrayList() }.let { if (entry.id !in it) it += entry.id }
            }
        }
        return out
    }

    /**
     * Links that point at nothing — the app's most useful list.
     *
     * Every one of these is a thing the project has referred to and never written down, which is
     * exactly the set of entries worth creating next. Ambiguous names are excluded: those are not
     * missing, they are over-supplied, and they need a rename rather than a new page.
     */
    fun brokenLinks(entries: List<LoreEntry>): List<String> {
        val index = index(entries)
        val out = LinkedHashMap<String, String>()
        entries.forEach { entry ->
            links(entry.body).forEach { target ->
                if (!index.knows(target)) out.putIfAbsent(LoreIndex.normalize(target), target)
            }
        }
        return out.values.toList()
    }

    /** Names claimed by more than one entry — the wiki's own contradictions, listed. */
    fun ambiguousNames(entries: List<LoreEntry>): List<String> {
        val counts = LinkedHashMap<String, MutableList<String>>()
        entries.forEach { entry ->
            (listOf(entry.name) + entry.aliases).filter { it.isNotBlank() }.forEach { name ->
                counts.getOrPut(LoreIndex.normalize(name)) { ArrayList() }.let {
                    if (entry.id !in it) it += entry.id
                }
            }
        }
        return counts.filterValues { it.size > 1 }.keys.toList()
    }

    /** Case-insensitive name/alias/summary search, for the lore screen's filter box. */
    fun search(entries: List<LoreEntry>, query: String): List<LoreEntry> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return entries
        return entries.filter { entry ->
            entry.name.lowercase().contains(needle) ||
                entry.aliases.any { it.lowercase().contains(needle) } ||
                entry.summary?.lowercase()?.contains(needle) == true ||
                entry.body.lowercase().contains(needle)
        }
    }
}
