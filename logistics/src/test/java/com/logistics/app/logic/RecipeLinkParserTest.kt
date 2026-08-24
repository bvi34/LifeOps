package com.logistics.app.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RecipeLinkParserTest {

    @Test
    fun parsesJsonLdRecipe() {
        val html = """
            <html><head>
            <script type="application/ld+json">
            {"@context":"https://schema.org","@type":"Recipe","name":"Test Pancakes",
             "recipeYield":"4 servings",
             "recipeIngredient":["2 cups flour","1 tbsp sugar","1/2 tsp salt"]}
            </script></head><body>...</body></html>
        """.trimIndent()

        val recipe = RecipeLinkParser.parse(html, "https://example.com/pancakes")
        assertNotNull(recipe)
        assertEquals("Test Pancakes", recipe!!.name)
        assertEquals(4.0, recipe.servings!!, 0.0001)
        assertEquals(3, recipe.ingredients.size)
        assertEquals("2 cups flour", recipe.ingredients.first())
        assertEquals("https://example.com/pancakes", recipe.sourceUrl)
    }

    @Test
    fun findsRecipeInsideGraph() {
        val html = """
            <script type="application/ld+json">
            {"@context":"https://schema.org","@graph":[
              {"@type":"WebPage","name":"A page"},
              {"@type":["Recipe"],"name":"Graph Cake","recipeYield":["8"],
               "recipeIngredient":["1 cup sugar","2 eggs"]}
            ]}
            </script>
        """.trimIndent()

        val recipe = RecipeLinkParser.parse(html)
        assertNotNull(recipe)
        assertEquals("Graph Cake", recipe!!.name)
        assertEquals(8.0, recipe.servings!!, 0.0001)
        assertEquals(2, recipe.ingredients.size)
    }

    @Test
    fun decodesHtmlEntitiesInNames() {
        val html = """
            <script type="application/ld+json">
            {"@type":"Recipe","name":"Mac &amp; Cheese","recipeIngredient":["8 oz macaroni"]}
            </script>
        """.trimIndent()

        val recipe = RecipeLinkParser.parse(html)
        assertEquals("Mac & Cheese", recipe!!.name)
    }

    @Test
    fun fallsBackToMicrodata() {
        val html = """
            <div itemscope itemtype="http://schema.org/Recipe">
              <h1 itemprop="name">Micro Soup</h1>
              <span itemprop="recipeYield">6</span>
              <ul>
                <li itemprop="recipeIngredient">3 carrots</li>
                <li itemprop="recipeIngredient">1 onion</li>
              </ul>
            </div>
        """.trimIndent()

        val recipe = RecipeLinkParser.parse(html)
        assertNotNull(recipe)
        assertEquals("Micro Soup", recipe!!.name)
        assertEquals(6.0, recipe.servings!!, 0.0001)
        assertEquals(2, recipe.ingredients.size)
    }

    @Test
    fun returnsNullWhenNoRecipeData() {
        assertNull(RecipeLinkParser.parse("<html><body><p>No recipe here.</p></body></html>"))
    }

    @Test
    fun parsesHowToStepInstructions() {
        val html = """
            <script type="application/ld+json">
            {"@type":"Recipe","name":"Stepped Stew","recipeIngredient":["1 lb beef"],
             "recipeInstructions":[
               {"@type":"HowToStep","text":"Brown the beef."},
               {"@type":"HowToStep","text":"Simmer for an hour."}
             ]}
            </script>
        """.trimIndent()

        val recipe = RecipeLinkParser.parse(html)
        assertEquals(listOf("Brown the beef.", "Simmer for an hour."), recipe!!.steps)
    }

    @Test
    fun flattensHowToSectionInstructions() {
        val html = """
            <script type="application/ld+json">
            {"@type":"Recipe","name":"Sectioned Cake","recipeIngredient":["2 eggs"],
             "recipeInstructions":[
               {"@type":"HowToSection","name":"Batter","itemListElement":[
                 {"@type":"HowToStep","text":"Beat the eggs."},
                 {"@type":"HowToStep","text":"Fold in the flour."}
               ]},
               {"@type":"HowToSection","name":"Bake","itemListElement":[
                 {"@type":"HowToStep","text":"Bake 30 minutes."}
               ]}
             ]}
            </script>
        """.trimIndent()

        val recipe = RecipeLinkParser.parse(html)
        assertEquals(
            listOf("Beat the eggs.", "Fold in the flour.", "Bake 30 minutes."),
            recipe!!.steps
        )
    }

    @Test
    fun splitsASingleInstructionsBlobIntoSteps() {
        val html = """
            <script type="application/ld+json">
            {"@type":"Recipe","name":"Blob Bread","recipeIngredient":["flour"],
             "recipeInstructions":"<p>Mix the dough.</p><p>Prove for an hour &amp; knock back.</p>"}
            </script>
        """.trimIndent()

        val recipe = RecipeLinkParser.parse(html)
        assertEquals(listOf("Mix the dough.", "Prove for an hour & knock back."), recipe!!.steps)
    }

    @Test
    fun keepsAnUnsplittableInstructionsStringAsOneStep() {
        val html = """
            <script type="application/ld+json">
            {"@type":"Recipe","name":"One Liner","recipeIngredient":["salt"],
             "recipeInstructions":"Stir everything together."}
            </script>
        """.trimIndent()

        assertEquals(listOf("Stir everything together."), RecipeLinkParser.parse(html)!!.steps)
    }

    @Test
    fun hasNoStepsWhenThePagePublishesNone() {
        val html = """
            <script type="application/ld+json">
            {"@type":"Recipe","name":"Ingredients Only","recipeIngredient":["1 cup water"]}
            </script>
        """.trimIndent()

        assertEquals(emptyList<String>(), RecipeLinkParser.parse(html)!!.steps)
    }

    @Test
    fun readsMicrodataInstructions() {
        val html = """
            <div itemscope itemtype="http://schema.org/Recipe">
              <h1 itemprop="name">Micro Soup</h1>
              <li itemprop="recipeIngredient">3 carrots</li>
              <li itemprop="recipeInstructions">Chop the carrots.</li>
              <li itemprop="recipeInstructions">Boil until soft.</li>
            </div>
        """.trimIndent()

        val recipe = RecipeLinkParser.parse(html)
        assertEquals(listOf("Chop the carrots.", "Boil until soft."), recipe!!.steps)
    }
}
