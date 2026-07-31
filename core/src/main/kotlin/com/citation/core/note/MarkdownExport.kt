package com.citation.core.note

/**
 * Renders captured notes to portable Markdown so they can leave Citation intact — the payoff end of
 * note-taking. Because a note freezes its `{title, author, snapshot}`, each rendered entry is a
 * self-contained, correctly-attributed reference: paste the export into Obsidian (or anything) and
 * every quote still says where it came from.
 *
 * Pure string-in/string-out (the caller formats any "generated on" date and passes it as [subtitle]),
 * so the whole layout is unit-testable without a device.
 */
object MarkdownExport {

    /**
     * @param notes the notes to render — already filtered by the caller if a subset is wanted
     *   (e.g. one tag), so "export what I'm looking at" is just passing the visible list.
     * @param heading the document's H1.
     * @param subtitle optional italic line under the heading (a date, a filter description, …).
     */
    fun render(
        notes: List<Note>,
        heading: String = "Citation Notes",
        subtitle: String? = null
    ): String {
        val sb = StringBuilder()
        sb.append("# ").append(heading).append("\n\n")
        subtitle?.takeIf { it.isNotBlank() }?.let { sb.append("_").append(it).append("_\n\n") }

        if (notes.isEmpty()) {
            sb.append("_No notes._\n")
            return sb.toString()
        }

        // Group under the source they came from, preserving first-seen order for a stable document.
        val grouped = LinkedHashMap<String, MutableList<Note>>()
        notes.forEach { note -> grouped.getOrPut(sourceLabel(note)) { mutableListOf() }.add(note) }

        grouped.entries.forEachIndexed { index, (label, group) ->
            if (index > 0) sb.append("\n")
            sb.append("## ").append(label).append("\n\n")
            group.forEach { note -> renderNote(sb, note) }
        }
        return sb.toString().trimEnd() + "\n"
    }

    private fun renderNote(sb: StringBuilder, note: Note) {
        note.references.forEach { ref ->
            // A snapshot may span lines; prefix each so the whole passage is one blockquote.
            ref.quotedSnapshot.lines().forEach { line -> sb.append("> ").append(line).append("\n") }
            sb.append("\n")
        }
        if (note.body.isNotBlank()) sb.append(note.body.trim()).append("\n\n")
        if (note.tags.isNotEmpty()) sb.append(Tags.format(note.tags)).append("\n\n")
    }

    private fun sourceLabel(note: Note): String {
        val author = note.source.author
        return if (author.isNullOrBlank()) note.source.title else "${note.source.title} — $author"
    }
}
