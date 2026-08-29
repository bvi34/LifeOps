package com.advisor.app.logic

/**
 * Answers **inventory** questions over the Logistics pantry and grocery list — the stock data you
 * already keep:
 *
 * - "what's running low?" / "what do I need to restock?" → the low-stock items;
 * - "how much flour do I have?" / "do I have milk?" → a specific item's stock;
 * - "what's in my pantry?" / "how many pantry items?" → a summary (count + category breakdown);
 * - "what's on my grocery list?" / "what do I need to buy?" → the items still needed.
 *
 * Stock numbers come from [DocumentFacts]. The Logistics permission is required: if it's off, the
 * function asks to enable it rather than reporting an empty pantry as "nothing". Pure and JVM-testable.
 */
class InventoryFunction : AdvisorFunction {

    override val name: String = "inventory"

    override fun handles(question: String): Boolean = intentOf(question) != null

    override fun run(request: FunctionRequest): FunctionResult {
        val intent = intentOf(request.question)
            ?: return FunctionResult("I couldn't tell what inventory you meant.")

        if (SourceApp.LOGISTICS !in request.grantedApps) {
            return FunctionResult(
                "That's in your Logistics pantry, which isn't enabled right now. Turn Logistics on in " +
                    "Permissions and ask again."
            )
        }

        val pantry = request.corpus.filter { it.source == SourceApp.LOGISTICS && it.kind == "pantry" }
        val grocery = request.corpus.filter { it.source == SourceApp.LOGISTICS && it.kind == "grocery" }

        return when (intent) {
            is Intent.Low -> lowStock(pantry)
            is Intent.Grocery -> groceryList(grocery)
            is Intent.Summary -> summary(pantry)
            is Intent.Lookup -> lookup(intent.item, pantry, grocery)
        }
    }

    // --- responses ---

    private fun lowStock(pantry: List<KnowledgeDocument>): FunctionResult {
        if (pantry.isEmpty()) return FunctionResult("Your pantry is empty — nothing to check.")
        val low = pantry.filter { DocumentFacts.isLowStock(it) }
        if (low.isEmpty()) return FunctionResult("Nothing is running low — your pantry is well stocked.")
        val text = buildString {
            append("Running low (${low.size} ${plural(low.size, "item")}):")
            for (doc in low) append("\n• ").append(stockLine(doc))
        }
        return FunctionResult(text, low.take(MAX_CITATIONS))
    }

    private fun groceryList(grocery: List<KnowledgeDocument>): FunctionResult {
        val needed = grocery.filter { DocumentFacts.groceryNeeded(it) }
        if (needed.isEmpty()) return FunctionResult("Your grocery list is clear — nothing marked as needed.")
        val text = buildString {
            append("On your grocery list (${needed.size} ${plural(needed.size, "item")}):")
            for (doc in needed) append("\n• ").append(doc.title)
        }
        return FunctionResult(text, needed.take(MAX_CITATIONS))
    }

    private fun summary(pantry: List<KnowledgeDocument>): FunctionResult {
        if (pantry.isEmpty()) return FunctionResult("Your pantry is empty.")
        val byCategory = pantry.groupingBy { DocumentFacts.category(it) ?: "Uncategorized" }.eachCount()
        val low = pantry.count { DocumentFacts.isLowStock(it) }
        val text = buildString {
            append("Your pantry has ${pantry.size} ${plural(pantry.size, "item")}")
            if (low > 0) append(" ($low running low)")
            append(".")
            byCategory.entries.sortedByDescending { it.value }.forEach { (cat, n) ->
                append("\n• ").append(cat).append(": ").append(n)
            }
        }
        return FunctionResult(text, pantry.take(MAX_CITATIONS))
    }

    private fun lookup(item: String, pantry: List<KnowledgeDocument>, grocery: List<KnowledgeDocument>): FunctionResult {
        val match = pantry.firstOrNull { it.title.contains(item, ignoreCase = true) }
            ?: pantry.firstOrNull { DocumentFacts.category(it)?.contains(item, ignoreCase = true) == true }
        if (match != null) {
            val low = if (DocumentFacts.isLowStock(match)) " — running low" else ""
            return FunctionResult("You have ${stockLine(match)}$low.", listOf(match))
        }
        // Not stocked — is it at least on the shopping list?
        val onList = grocery.firstOrNull { it.title.contains(item, ignoreCase = true) }
        if (onList != null) {
            return FunctionResult("You don't have \"$item\" in the pantry, but it's on your grocery list.", listOf(onList))
        }
        return FunctionResult("I don't see \"$item\" in your pantry or on your grocery list.")
    }

    // --- helpers ---

    private fun stockLine(doc: KnowledgeDocument): String {
        val qty = DocumentFacts.stockQuantity(doc)
        val unit = DocumentFacts.stockUnit(doc)
        return when {
            qty == null -> doc.title
            unit != null -> "${doc.title}: ${trimNumber(qty)} $unit"
            else -> "${doc.title}: ${trimNumber(qty)}"
        }
    }

    private fun trimNumber(value: Double): String =
        if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

    private fun plural(n: Int, noun: String): String = if (n == 1) noun else noun + "s"

    // --- intent parsing ---

    private sealed interface Intent {
        object Low : Intent
        object Grocery : Intent
        object Summary : Intent
        data class Lookup(val item: String) : Intent
    }

    private fun intentOf(question: String): Intent? {
        val q = question.trim().removeSuffix("?").trim()
        if (q.isEmpty()) return null

        if (LOW.containsMatchIn(q)) return Intent.Low
        if (GROCERY.containsMatchIn(q)) return Intent.Grocery

        LOOKUP.find(q)?.let { m ->
            // The item is in group 1 ("how much/many …") or group 2 ("do I have …"), whichever matched.
            val raw = m.groupValues[1].ifBlank { m.groupValues[2] }
            val item = cleanItem(raw)
            if (item.isNotBlank() && item !in LOOKUP_STOP) return Intent.Lookup(item)
        }

        if (SUMMARY.containsMatchIn(q)) return Intent.Summary
        return null
    }

    /** Strip filler ("any", "some", "the", "left", trailing "in stock") from a captured item phrase. */
    private fun cleanItem(raw: String): String =
        raw.trim()
            .replace(Regex("""(?i)^(?:any|some|the|a|an)\s+"""), "")
            .replace(Regex("""(?i)\s+(?:left|remaining|in\s+stock|in\s+the\s+pantry|in\s+my\s+pantry|on\s+hand)\s*$"""), "")
            .trim()

    private companion object {
        const val MAX_CITATIONS = 12

        val LOW = Regex(
            """(?i)\b(running low|low (?:on|in) stock|what'?s low|what is low|restock|out of stock|need(?:ed)? to restock)\b"""
        )
        val GROCERY = Regex(
            """(?i)\b(grocery list|shopping list|groceries|need to buy|need to get|left to buy|what to buy|buy list)\b"""
        )
        val SUMMARY = Regex(
            """(?i)\b(?:pantry|inventory|in stock)\b"""
        )
        // "how much/many <item> do I have | is/are left | in stock", or "do I have <item> in (stock|the pantry)"
        val LOOKUP = Regex(
            """(?i)\bhow (?:much|many) (.+?) (?:do i have|are left|is left|left|in stock|in the pantry|in my pantry)\b""" +
                """|\bdo i have (.+?) (?:in stock|left|on hand|in the pantry|in my pantry)\b"""
        )

        // Words that are never a specific pantry item — either other apps' nouns or generic terms that
        // should route to the pantry summary (or another function) instead of an item lookup.
        val LOOKUP_STOP = setOf(
            "task", "tasks", "book", "books", "note", "notes", "operation", "operations",
            "project", "projects",
            "milestone", "milestones", "memory", "memories", "profile", "profiles", "time", "money",
            "pantry", "pantry item", "pantry items", "item", "items", "stock", "inventory",
            "everything", "anything", "food", "things", "stuff"
        )
    }
}
