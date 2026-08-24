package com.advisor.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CorpusMergeTest {

    private fun citationBook(title: String, body: String) =
        KnowledgeDocument("citation:book:$title", SourceApp.CITATION, "book", title, body)

    private fun lifeOpsBook(title: String, body: String) =
        KnowledgeDocument("lifeops:book:$title", SourceApp.LIFEOPS, "book", title, body)

    @Test
    fun folds_the_same_book_held_by_both_apps_into_the_lifeops_record() {
        val merged = CorpusMerge.merge(
            listOf(
                citationBook("Dune", "Book: Dune by Herbert. Source: EPUB. Reading state: READING"),
                lifeOpsBook("Dune", "Book: Dune by Herbert. Source: EPUB. Reading state: READING. Read for 120 min")
            )
        )
        val book = merged.single()
        assertEquals(SourceApp.LIFEOPS, book.source)
        assertTrue(book.body.contains("120 min"))
    }

    @Test
    fun matches_titles_regardless_of_case_and_padding() {
        val merged = CorpusMerge.merge(
            listOf(
                citationBook("  dune ", "Book: dune. Reading state: DONE"),
                lifeOpsBook("Dune", "Book: Dune. Reading state: DONE")
            )
        )
        assertEquals(1, merged.size)
    }

    @Test
    fun carries_the_favourite_flag_across_since_only_citation_records_it() {
        val merged = CorpusMerge.merge(
            listOf(
                citationBook("Dune", "Book: Dune. Reading state: DONE. Favourite."),
                lifeOpsBook("Dune", "Book: Dune. Reading state: DONE. Read for 400 min")
            )
        )
        val book = merged.single()
        assertEquals(SourceApp.LIFEOPS, book.source)
        assertTrue(book.body.endsWith("Favourite."))
        assertTrue(book.body.contains("400 min"))
    }

    @Test
    fun leaves_a_book_only_one_app_holds_alone() {
        val docs = listOf(
            citationBook("Dune", "Book: Dune. Reading state: READING"),
            lifeOpsBook("Neuromancer", "Book: Neuromancer. Reading state: TO_READ")
        )
        assertEquals(2, CorpusMerge.merge(docs).size)
    }

    @Test
    fun leaves_records_with_no_counterpart_alone() {
        val docs = listOf(
            citationBook("Dune", "Book: Dune. Reading state: READING"),
            lifeOpsBook("Dune", "Book: Dune. Reading state: READING"),
            KnowledgeDocument("citation:note:1", SourceApp.CITATION, "note", "Dune", "Note on \"Dune\": spice"),
            KnowledgeDocument("lifeops:task:1", SourceApp.LIFEOPS, "task", "Dune", "Task: Dune. Status: todo")
        )
        val merged = CorpusMerge.merge(docs)
        assertEquals(3, merged.size)
        assertTrue(merged.any { it.kind == "note" })
        assertTrue(merged.any { it.kind == "task" })
    }

    @Test
    fun folds_by_row_key_even_when_the_titles_have_diverged() {
        // LifeOps stores a synced book under Citation's own key, and its title is the user's to
        // rename — so the key is what still identifies the pair afterwards.
        val merged = CorpusMerge.merge(
            listOf(
                KnowledgeDocument("citation:book:ER:Book:7", SourceApp.CITATION, "book", "Dune", "Book: Dune."),
                KnowledgeDocument("lifeops:book:ER:Book:7", SourceApp.LIFEOPS, "book", "Dune (reread)", "Book: Dune (reread).")
            )
        )
        assertEquals("Dune (reread)", merged.single().title)
    }

    @Test
    fun a_corpus_with_only_one_source_is_returned_untouched() {
        val docs = listOf(citationBook("Dune", "Book: Dune. Reading state: READING"))
        assertEquals(docs, CorpusMerge.merge(docs))
    }

    @Test
    fun drops_the_lifeops_mirror_of_a_synced_note_and_keeps_citations() {
        // A highlight captured in Citation is synced onto the book's LifeOps notes under the id
        // "citation:<noteKey>", so the pair is recognisable exactly.
        val merged = CorpusMerge.merge(
            listOf(
                KnowledgeDocument("citation:note:n7", SourceApp.CITATION, "note", "Dune", "Note on \"Dune\" by Herbert: spice"),
                KnowledgeDocument("lifeops:note:citation:n7", SourceApp.LIFEOPS, "note", "Dune", "Note on \"Dune\": spice")
            )
        )
        val note = merged.single()
        assertEquals(SourceApp.CITATION, note.source)
        assertTrue(note.body.contains("Herbert"))
    }

    @Test
    fun keeps_a_note_written_in_lifeops() {
        val docs = listOf(
            KnowledgeDocument("citation:note:n7", SourceApp.CITATION, "note", "Dune", "Note on \"Dune\": spice"),
            KnowledgeDocument("lifeops:note:local-1", SourceApp.LIFEOPS, "note", "Dune", "Note on \"Dune\": my own")
        )
        assertEquals(2, CorpusMerge.merge(docs).size)
    }
}
