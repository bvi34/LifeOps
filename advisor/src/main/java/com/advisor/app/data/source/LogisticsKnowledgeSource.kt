package com.advisor.app.data.source

import android.content.Context
import com.advisor.app.logic.KnowledgeDocument
import com.advisor.app.logic.SourceApp
import com.logistics.app.data.db.LogisticsDatabase

/**
 * Reads Logistics' pantry stock and grocery list into [KnowledgeDocument]s, so Advisor can answer
 * "what's running low" or "do I have flour" from the same rows the pantry screen shows. Recipes and
 * foods live in LifeOps' catalog (Logistics doesn't own them), so those come in via the LifeOps
 * source, not here — no double indexing.
 */
class LogisticsKnowledgeSource(context: Context) : KnowledgeSource {

    private val appContext = context.applicationContext
    override val source = SourceApp.LOGISTICS

    override suspend fun load(): List<KnowledgeDocument> {
        val dao = LogisticsDatabase.getInstance(appContext).pantryDao()
        val docs = ArrayList<KnowledgeDocument>()

        for (item in dao.getAll()) {
            val threshold = item.lowStockThreshold
            val low = threshold != null && item.quantity <= threshold
            docs += KnowledgeDocument(
                id = "logistics:pantry:${item.id}",
                source = source,
                kind = "pantry",
                title = item.name,
                body = buildString {
                    append("Pantry item: ").append(item.name)
                    append(". In stock: ").append(trimNumber(item.quantity)).append(' ').append(item.unit)
                    item.category?.takeIf { it.isNotBlank() }?.let { append(". Category: ").append(it) }
                    if (low) append(". Running low.")
                    item.note?.takeIf { it.isNotBlank() }?.let { append(". Note: ").append(it) }
                }
            )
        }

        for (grocery in dao.getAllGrocery()) {
            docs += KnowledgeDocument(
                id = "logistics:grocery:${grocery.id}",
                source = source,
                kind = "grocery",
                title = grocery.name,
                body = buildString {
                    append("Grocery list item: ").append(grocery.name)
                    append(", ").append(trimNumber(grocery.quantity)).append(' ').append(grocery.unit)
                    append(if (grocery.checked) " (bought)" else " (needed)")
                    grocery.category?.takeIf { it.isNotBlank() }?.let { append(". Category: ").append(it) }
                }
            )
        }

        return docs
    }

    /** Render 2.0 as "2" but keep 2.5 as "2.5" — pantry quantities read better without trailing .0. */
    private fun trimNumber(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
}
