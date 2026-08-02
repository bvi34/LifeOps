package com.logistics.app.logic

/**
 * Framework-free heuristics that read a grocery product name and guess (a) a human packaging unit
 * for the pantry shelf ("Bag", "Can", "Gallon", …) and (b) a coarse aisle category. Both are only
 * suggestions the import preview shows and the user can override — they never need to be perfect,
 * just helpful. Kept pure so it's unit-tested on the JVM.
 */
object PantryUnits {

    /**
     * Packaging nouns we recognize as a countable pantry unit, longest/most-specific first so
     * "Canister" wins over "Can". Matched case-insensitively as whole words anywhere in the name;
     * the last match wins because the packaging descriptor is almost always at the end of a Walmart
     * product name ("…, 5.5 oz Canister").
     */
    private val UNIT_WORDS = listOf(
        "Canister", "Container", "Gallon", "Bottle", "Carton", "Bag", "Box", "Can", "Jar",
        "Jug", "Tub", "Tray", "Block", "Loaf", "Roll", "Pack", "Count", "Each", "Pouch", "Case"
    )

    /** Normalize a few near-synonyms so the shelf reads consistently. */
    private val UNIT_CANONICAL = mapOf(
        "Count" to "Count",
        "Each" to "each",
        "Pack" to "Pack"
    )

    fun guessUnit(name: String): String {
        val lower = name.lowercase()
        var best: Pair<Int, String>? = null // (index, unit)
        for (word in UNIT_WORDS) {
            val idx = lastWholeWordIndex(lower, word.lowercase())
            if (idx >= 0 && (best == null || idx > best!!.first)) best = idx to word
        }
        val chosen = best?.second ?: return "unit"
        return UNIT_CANONICAL[chosen] ?: chosen
    }

    private fun lastWholeWordIndex(haystack: String, word: String): Int {
        var from = 0
        var last = -1
        while (true) {
            val i = haystack.indexOf(word, from)
            if (i < 0) break
            val before = if (i == 0) ' ' else haystack[i - 1]
            val afterIdx = i + word.length
            val after = if (afterIdx >= haystack.length) ' ' else haystack[afterIdx]
            if (!before.isLetterOrDigit() && !after.isLetterOrDigit()) last = i
            from = i + 1
        }
        return last
    }

    /** Keyword → aisle category. First keyword hit (scanned in this order) wins, so more specific
     *  groups (pet food, household) are checked before broad ones. */
    private val CATEGORY_RULES: List<Pair<String, List<String>>> = listOf(
        "Pet" to listOf("cat food", "cat chow", "dog food", "pedigree", "purina", "hairball"),
        "Personal Care" to listOf("bubble bath", "shampoo", "soap", "toothpaste", "hypoallergenic"),
        "Household" to listOf("sandwich bags", "freezer bags", "zipper", "slider", "storage bag", "trash", "foil", "wrap"),
        "Frozen" to listOf("frozen", "tater tots", "pizza", "meatballs", "nuggets", "pancakes"),
        "Produce" to listOf("banana", "apple", "potato", "russet", "lettuce", "onion", "tomato", "carrot", "celery", "berries", "grapes"),
        "Meat & Seafood" to listOf("pork", "chicken", "beef", "bacon", "ham", "hot dogs", "catfish", "sausage", "loin", "turkey", "fish"),
        "Dairy & Eggs" to listOf("cheese", "mozzarella", "cream cheese", "butter", "milk", "yogurt", "egg", "string cheese"),
        "Beverages" to listOf("soda", "cola", "root beer", "juice", "punch", "coffee", "pepsi", "a&w", "water", "tea", "drink"),
        "Bakery" to listOf("bread", "tortilla", "biscuit", "rolls", "breadstick", "buns", "bagel", "cinnamon"),
        "Snacks" to listOf("chips", "crisps", "popcorn", "cereal", "bars", "pies", "smiles", "doritos", "ruffles", "pringles", "crackers", "candy", "cookies"),
        "Pantry" to listOf("sauce", "pasta", "rotini", "macaroni", "peanut butter", "bread crumbs", "syrup", "spread", "mayo", "alfredo", "flour", "sugar", "rice", "beans", "soup", "oil", "dressing")
    )

    fun guessCategory(name: String): String? {
        val lower = name.lowercase()
        for ((category, keywords) in CATEGORY_RULES) {
            if (keywords.any { lower.contains(it) }) return category
        }
        return null
    }
}
