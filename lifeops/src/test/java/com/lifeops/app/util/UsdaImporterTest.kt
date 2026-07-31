package com.lifeops.app.util

import com.lifeops.app.data.model.FoodSource
import org.junit.Assert.*
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

class UsdaImporterTest {

    private fun reader(text: String): BufferedReader = BufferedReader(StringReader(text.trimIndent()))

    private val foodCsv = """
        fdc_id,data_type,description,publication_date
        1001,foundation_food,"Apple, raw",2020-01-01
        1002,sr_legacy_food,Banana,2020-01-01
        1003,branded_food,Protein Bar,2020-01-01
        1004,survey_fndds_food,Survey Item,2020-01-01
    """

    private val foodNutrientCsv = """
        id,fdc_id,nutrient_id,amount
        1,1001,1008,52.0
        2,1001,1005,14.0
        3,1001,1003,0.3
        4,1001,1004,0.2
        5,1002,1008,89.0
        6,1002,1005,23.0
        7,1003,1008,400.0
        8,1003,1005,40.0
        9,1003,1003,20.0
        10,1003,1004,10.0
    """

    private val foodPortionCsv = """
        id,fdc_id,seq_num,amount,gram_weight,portion_description
        1,1001,2,1,182.0,1 large
        2,1001,1,1,100.0,1 medium
    """

    private val brandedFoodCsv = """
        fdc_id,brand_owner,brand_name,serving_size,serving_size_unit
        1003,Acme Co,Acme Bars,60,g
    """

    @Test
    fun `imports Foundation and SR Legacy by default and skips Branded`() {
        val items = UsdaImporter.import(reader(foodCsv), reader(foodNutrientCsv))
        assertEquals(setOf("usda-1001", "usda-1002"), items.map { it.id }.toSet())
        assertTrue(items.all { it.source == FoodSource.UsdaFoundation })
    }

    @Test
    fun `includeBranded pulls branded rows in as UsdaBranded`() {
        val items = UsdaImporter.import(
            reader(foodCsv), reader(foodNutrientCsv),
            brandedFoodCsv = reader(brandedFoodCsv), includeBranded = true
        )
        val bar = items.single { it.id == "usda-1003" }
        assertEquals(FoodSource.UsdaBranded, bar.source)
        assertEquals("Acme Co", bar.brand)
    }

    @Test
    fun `survey data type is never imported`() {
        val items = UsdaImporter.import(
            reader(foodCsv), reader(foodNutrientCsv),
            brandedFoodCsv = reader(brandedFoodCsv), includeBranded = true
        )
        assertTrue(items.none { it.fdcId == 1004L })
    }

    @Test
    fun `food_portion gram weight normalizes per-100g nutrients into per-serving, preferring lowest seq_num`() {
        val items = UsdaImporter.import(reader(foodCsv), reader(foodNutrientCsv), foodPortionCsv = reader(foodPortionCsv))
        val apple = items.single { it.id == "usda-1001" }
        // seq_num 1 (100g) wins over seq_num 2 (182g)
        assertEquals(100.0, apple.servingSizeGrams)
        assertEquals(52.0, apple.calories, 0.0001) // 52 kcal/100g * (100/100)
        assertEquals(14.0, apple.carbsG, 0.0001)
    }

    @Test
    fun `with no portion or branded data the serving falls back to the 100g basis itself`() {
        val items = UsdaImporter.import(reader(foodCsv), reader(foodNutrientCsv))
        val banana = items.single { it.id == "usda-1002" }
        assertEquals(100.0, banana.servingSizeGrams)
        assertEquals(89.0, banana.calories, 0.0001)
    }

    @Test
    fun `branded serving size in grams normalizes nutrients off the 100g basis`() {
        val items = UsdaImporter.import(
            reader(foodCsv), reader(foodNutrientCsv),
            brandedFoodCsv = reader(brandedFoodCsv), includeBranded = true
        )
        val bar = items.single { it.id == "usda-1003" }
        assertEquals(60.0, bar.servingSizeGrams)
        // 400 kcal/100g * 0.6 = 240 kcal/serving
        assertEquals(240.0, bar.calories, 0.0001)
        assertEquals(24.0, bar.carbsG, 0.0001)
        assertEquals(12.0, bar.proteinG, 0.0001)
        assertEquals(6.0, bar.fatG, 0.0001)
    }

    @Test
    fun `a food with no energy nutrient row is skipped`() {
        val foodCsvNoEnergy = """
            fdc_id,data_type,description
            2001,foundation_food,No Energy Food
        """
        val nutrientCsvNoEnergy = """
            id,fdc_id,nutrient_id,amount
            1,2001,1005,10.0
        """
        val items = UsdaImporter.import(reader(foodCsvNoEnergy), reader(nutrientCsvNoEnergy))
        assertTrue(items.isEmpty())
    }

    @Test
    fun `re-importing the same CSVs is idempotent via deterministic fdcId-derived ids`() {
        val first = UsdaImporter.import(reader(foodCsv), reader(foodNutrientCsv))
        val second = UsdaImporter.import(reader(foodCsv), reader(foodNutrientCsv))
        assertEquals(first.map { it.id }.toSet(), second.map { it.id }.toSet())
        assertEquals(UsdaImporter.foodItemId(1001L), "usda-1001")
    }
}
