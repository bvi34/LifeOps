package com.project.app.logic

/** One document, as the compiler sees it. */
data class ManuscriptDoc(
    val id: String,
    val outlineNodeId: String?,
    val title: String,
    val sortOrder: Int,
    val blocks: List<DocBlock>
)

/** What to put in, and what to leave out. */
data class CompileOptions(
    /**
     * Cut material is out by default, because that is what cut means. It can be put back in — a
     * "what did this look like before I cut it" read is a real thing people want.
     */
    val includeCut: Boolean = false,
    /** Synopses as block quotes under each heading — an outline-with-text read, for revision. */
    val includeSynopses: Boolean = false,
    /** Headings for the outline's own structure. Off gives continuous prose with separators only. */
    val includeHeadings: Boolean = true,
    /** Documents that belong to no outline node, gathered at the end rather than dropped. */
    val includeUnplaced: Boolean = false,
    /** Between two documents filed under the same piece. */
    val separator: String = "* * *"
)

/** One piece of the compiled whole, in outline order. */
data class ManuscriptPiece(
    val nodeId: String,
    val number: String,
    val depth: Int,
    val title: String,
    val status: OutlineStatus,
    val synopsis: String?,
    /** The rendered text of every document filed under this piece; empty when nothing is written. */
    val body: String,
    val words: Int,
    val docCount: Int
) {
    /** A piece of the outline with nothing written for it. */
    val isEmpty: Boolean get() = docCount == 0
}

/**
 * The whole outline and everything written for it, as one document.
 *
 * This is the operation the app exists to make possible. The outline knows the order, the documents
 * hold the text, and the link between them is already maintained on every edit — so "give me the
 * manuscript" is a walk rather than a feature. Nothing is stored: a manuscript is derived on demand
 * and thrown away.
 *
 * The decision that shapes the rest: **a hole is reported, never hidden.** A piece of the outline
 * with nothing written for it contributes its heading and no text, and lands in [gaps]. A compiler
 * that silently skipped it would hand you a manuscript with a scene missing and no way to know —
 * which is exactly the failure you would discover after sending it to somebody. Same for documents
 * belonging to no piece of the outline: they are counted in [unplaced] even when they are not
 * included, because "12 documents are not in this export" is the sentence that saves you.
 *
 * The [options] it was compiled under are carried on the result and [render] takes none of its own.
 * That is deliberate: rendering under different options than the compile used would quietly disagree
 * with the summary printed beside it — the cut scene counted as omitted and then printed anyway.
 */
data class Manuscript(
    val title: String,
    val pieces: List<ManuscriptPiece>,
    val words: Int,
    /** Leaves of the outline with no document filed under them, in order. */
    val gaps: List<ManuscriptPiece>,
    /** Documents belonging to no outline node. Included only when asked; always counted. */
    val unplaced: List<ManuscriptDoc>,
    /** Cut pieces left out of this compile. */
    val cutOmitted: Int,
    val options: CompileOptions
) {

    /** The manuscript as Markdown — the thing you actually copy out. */
    fun render(): String {
        val out = StringBuilder()
        out.append("# $title\n\n")

        pieces.forEach { piece ->
            if (options.includeHeadings) {
                val level = (piece.depth + 2).coerceAtMost(MAX_HEADING)
                out.append("${"#".repeat(level)} ${piece.title}\n\n")
            }
            if (options.includeSynopses) {
                piece.synopsis?.let { out.append("> $it\n\n") }
            }
            if (piece.body.isNotBlank()) {
                out.append(piece.body).append("\n\n")
            }
        }

        if (options.includeUnplaced && unplaced.isNotEmpty()) {
            out.append("## Unplaced\n\n")
            unplaced.forEach { doc ->
                out.append("### ${doc.title}\n\n")
                val body = DocBlocks.render(doc.blocks)
                if (body.isNotBlank()) out.append(body).append("\n\n")
            }
        }

        return out.toString().trimEnd('\n') + "\n"
    }

    /** The line the compile screen leads with: how big it is, and what it could not include. */
    val summary: String
        get() {
            val parts = ArrayList<String>(4)
            parts += "${ProjectPulse.count(words)} words"
            parts += "${pieces.size} ${ProjectPulse.plural(pieces.size, "piece")}"
            if (gaps.isNotEmpty()) parts += "${gaps.size} with nothing written"
            if (unplaced.isNotEmpty()) {
                parts += "${unplaced.size} unplaced ${ProjectPulse.plural(unplaced.size, "document")}"
            }
            if (cutOmitted > 0) parts += "$cutOmitted cut"
            return parts.joinToString(" · ")
        }

    companion object {

        /** Markdown allows six heading levels; deeper nesting flattens onto the last one. */
        private const val MAX_HEADING = 6

        fun compile(
            projectName: String,
            rows: List<OutlineRow>,
            docs: List<ManuscriptDoc>,
            options: CompileOptions = CompileOptions()
        ): Manuscript {
            val byNode = docs
                .filter { it.outlineNodeId != null }
                .groupBy { it.outlineNodeId!! }
                .mapValues { (_, filed) ->
                    filed.sortedWith(compareBy({ it.sortOrder }, { it.title.lowercase() }))
                }

            val pieces = ArrayList<ManuscriptPiece>(rows.size)
            var cutOmitted = 0

            rows.forEach { row ->
                if (!row.node.status.counts && !options.includeCut) {
                    cutOmitted++
                    return@forEach
                }
                val filed = byNode[row.node.id].orEmpty()
                val body = filed.joinToString("\n\n${options.separator}\n\n") { DocBlocks.render(it.blocks) }
                pieces += ManuscriptPiece(
                    nodeId = row.node.id,
                    number = row.number,
                    depth = row.depth,
                    title = row.node.title,
                    status = row.node.status,
                    synopsis = row.node.synopsis?.takeIf { it.isNotBlank() },
                    body = body,
                    words = filed.sumOf { DocBlocks.wordCount(it.blocks) },
                    docCount = filed.size
                )
            }

            val unplaced = docs
                .filter { it.outlineNodeId == null }
                .sortedWith(compareBy({ it.sortOrder }, { it.title.lowercase() }))

            val placedWords = pieces.sumOf { it.words }
            val unplacedWords =
                if (options.includeUnplaced) unplaced.sumOf { DocBlocks.wordCount(it.blocks) } else 0

            return Manuscript(
                title = projectName,
                pieces = pieces,
                words = placedWords + unplacedWords,
                // Only leaves count as gaps: an act is *supposed* to have no text of its own.
                gaps = pieces.filterIndexed { index, piece -> piece.isEmpty && !hasChild(pieces, index) },
                unplaced = unplaced,
                cutOmitted = cutOmitted,
                options = options
            )
        }

        /**
         * Whether some later piece sits underneath the one at [index].
         *
         * Read off the compiled order rather than the tree: pieces are already depth-first, so a
         * node's children are exactly the rows that follow it while staying deeper than it. That
         * keeps this honest when [CompileOptions.includeCut] has removed rows from mid-branch.
         */
        private fun hasChild(pieces: List<ManuscriptPiece>, index: Int): Boolean {
            val piece = pieces[index]
            val next = pieces.getOrNull(index + 1) ?: return false
            return next.depth > piece.depth
        }
    }
}
