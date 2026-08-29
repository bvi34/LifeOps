package com.logistics.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipeTextParserTest {

    /** The common case: a screenshot of a recipe card, headers and all. */
    @Test
    fun readsTitleServingsIngredientsAndSteps() {
        val text = """
            Grandma's Chili
            ★★★★★ 4.8 from 212 votes
            Prep time 15 mins
            Cook time 45 mins
            Serves 6

            Ingredients
            1 lb ground beef
            2 cans kidney beans, drained
            1 onion, diced
            2 tbsp chili powder

            Directions
            1. Brown the beef in a heavy pot.
            2. Add the onion and cook until soft.
            3. Stir in everything else and simmer 30 minutes.
        """.trimIndent()

        val parsed = RecipeTextParser.parse(text)

        assertEquals("Grandma's Chili", parsed.name)
        assertEquals(6.0, parsed.servings!!, 0.0001)
        assertEquals(
            listOf("1 lb ground beef", "2 cans kidney beans, drained", "1 onion, diced", "2 tbsp chili powder"),
            parsed.ingredients
        )
        assertEquals(3, parsed.steps.size)
        assertEquals("Brown the beef in a heavy pot.", parsed.steps.first())
        assertEquals("Stir in everything else and simmer 30 minutes.", parsed.steps.last())
    }

    /** Page furniture between the header and the list must not become an ingredient. */
    @Test
    fun dropsPageFurniture() {
        val text = """
            Banana Bread
            Print Save Share
            Total time 1 hr 10 mins
            Ingredients
            Nutrition per slice: 210 calories
            2 cups flour
            3 ripe bananas
        """.trimIndent()

        val parsed = RecipeTextParser.parse(text)

        assertEquals("Banana Bread", parsed.name)
        assertEquals(listOf("2 cups flour", "3 ripe bananas"), parsed.ingredients)
    }

    /** Bullets and checkboxes are layout, not text. */
    @Test
    fun stripsBulletsAndCheckboxes() {
        val text = """
            Ingredients
            ▢ 1 cup rice
            • 2 cups water
            - 1 tsp salt
        """.trimIndent()

        val parsed = RecipeTextParser.parse(text)

        assertEquals(listOf("1 cup rice", "2 cups water", "1 tsp salt"), parsed.ingredients)
    }

    /** A crop of just the list: no header to trust, so lines that open with an amount are the list. */
    @Test
    fun readsAnUnlabelledListByItsQuantities() {
        val text = """
            Weeknight Pasta
            1 lb spaghetti
            3 cloves garlic
            olive oil
        """.trimIndent()

        val parsed = RecipeTextParser.parse(text)

        assertEquals("Weeknight Pasta", parsed.name)
        assertEquals(listOf("1 lb spaghetti", "3 cloves garlic"), parsed.ingredients)
        assertTrue(parsed.steps.isEmpty())
    }

    /** "Makes 12" and "4 servings" are the other two ways a page says how much it makes. */
    @Test
    fun readsServingsFromMakesAndServingsPhrasing() {
        assertEquals(
            12.0,
            RecipeTextParser.parse("Cookies\nMakes 12 cookies\nIngredients\n1 cup sugar").servings!!,
            0.0001
        )
        assertEquals(
            4.0,
            RecipeTextParser.parse("Soup\n4 servings\nIngredients\n1 onion").servings!!,
            0.0001
        )
    }

    /** Nothing said how much it makes — better null than a guessed 1. */
    @Test
    fun leavesServingsNullWhenThePageDoesntSay() {
        val parsed = RecipeTextParser.parse("Toast\nIngredients\n2 slices bread")
        assertNull(parsed.servings)
    }

    /** "Step 2:" numbering belongs to the layout; the step is the sentence after it. */
    @Test
    fun stripsStepNumbering() {
        val text = """
            Rice
            Ingredients
            1 cup rice
            Method
            Step 1: Rinse the rice.
            Step 2: Simmer covered for 18 minutes.
        """.trimIndent()

        val parsed = RecipeTextParser.parse(text)

        assertEquals(listOf("Rinse the rice.", "Simmer covered for 18 minutes."), parsed.steps)
    }

    /** Two screenshots of one page overlap at the seam; the repeat isn't a second ingredient. */
    @Test
    fun collapsesTheSeamBetweenTwoScreenshots() {
        val first = "Chili\nIngredients\n1 lb ground beef\n2 cans kidney beans"
        val second = "2 cans kidney beans\n1 onion, diced"

        val parsed = RecipeTextParser.parse("$first\n$second")

        assertEquals(listOf("1 lb ground beef", "2 cans kidney beans", "1 onion, diced"), parsed.ingredients)
    }

    /** An unreadable screenshot yields nothing rather than throwing — the caller can still type. */
    @Test
    fun emptyTextParsesToAnEmptyRecipe() {
        val parsed = RecipeTextParser.parse("   \n \n", fallbackName = "Screenshot recipe")
        assertEquals("Screenshot recipe", parsed.name)
        assertTrue(parsed.ingredients.isEmpty())
        assertTrue(parsed.steps.isEmpty())
    }
}
