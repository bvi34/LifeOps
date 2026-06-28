package com.lifeops.app.util

import com.lifeops.app.data.model.FoodItem
import com.lifeops.app.data.model.FoodSource
import java.io.BufferedReader

/**
 * Importer for the USDA FoodData Central *bulk CSV download* (not the live API — offline, no
 * rate limits, fits a self-hosted app). Defaults to Foundation + SR Legacy (whole foods, high
 * quality, small); Branded (~300K+ rows, mostly noise for a household) is opt-in.
 *
 * Idempotent and re-runnable: a FoodItem's id is derived from its fdc_id, so importing the
 * same CSVs twice REPLACEs the same rows instead of duplicating them.
 */
object UsdaImporter {
    // Stable FoodData Central nutrient ids, as referenced by food_nutrient.csv's nutrient_id
    // column (NOT the nutrient_nbr column) — these don't change between FDC releases.
    private const val NUTRIENT_ENERGY_KCAL = 1008L
    private const val NUTRIENT_PROTEIN = 1003L
    private const val NUTRIENT_FAT = 1004L
    private const val NUTRIENT_CARBS = 1005L
    private const val NUTRIENT_FIBER = 1079L
    private const val NUTRIENT_SODIUM = 1093L

    private data class RawFood(val fdcId: Long, val description: String, val dataType: String)

    private data class BrandedInfo(
        val brandOwner: String?,
        val brandName: String?,
        val servingSize: Double?,
        val servingSizeUnit: String?
    )

    fun foodItemId(fdcId: Long): String = "usda-$fdcId"

    /**
     * @param foodCsv USDA food.csv
     * @param foodNutrientCsv USDA food_nutrient.csv (amounts are per 100g)
     * @param foodPortionCsv USDA food_portion.csv — supplies the gram weight of a serving for
     *   Foundation/SR Legacy foods (which have no branded_food serving_size). Optional.
     * @param brandedFoodCsv USDA branded_food.csv — supplies brand + serving size for branded
     *   foods. Required (non-null) iff includeBranded is true and branded rows should resolve.
     * @param includeBranded if false (the default), branded_food rows in food.csv are skipped.
     */
    fun import(
        foodCsv: BufferedReader,
        foodNutrientCsv: BufferedReader,
        foodPortionCsv: BufferedReader? = null,
        brandedFoodCsv: BufferedReader? = null,
        includeBranded: Boolean = false
    ): List<FoodItem> {
        val foods = parseFood(foodCsv, includeBranded)
        if (foods.isEmpty()) return emptyList()

        val nutrientsByFood = parseFoodNutrients(foodNutrientCsv, foods.keys)
        val gramWeightByFood = foodPortionCsv?.let { parsePortions(it, foods.keys) } ?: emptyMap()
        val brandedByFood = brandedFoodCsv?.let { parseBranded(it, foods.keys) } ?: emptyMap()

        return foods.values.mapNotNull { raw ->
            buildFoodItem(raw, nutrientsByFood[raw.fdcId].orEmpty(), gramWeightByFood[raw.fdcId], brandedByFood[raw.fdcId])
        }
    }

    private fun buildFoodItem(
        raw: RawFood,
        nutrientsPer100g: Map<Long, Double>,
        gramWeight: Double?,
        branded: BrandedInfo?
    ): FoodItem? {
        // No energy value means the row is unusable for nutrition purposes — skip it.
        val caloriesPer100g = nutrientsPer100g[NUTRIENT_ENERGY_KCAL] ?: return null

        val source = if (raw.dataType == "branded_food") FoodSource.UsdaBranded else FoodSource.UsdaFoundation

        // Resolve the gram weight of "one serving": prefer branded_food's serving_size when
        // it's already gram-denominated, then a USDA food_portion row, then fall back to
        // treating 100g itself as the serving (no guessed density conversions).
        val servingSizeGrams: Double = when {
            branded?.servingSizeUnit?.equals("g", ignoreCase = true) == true && branded.servingSize != null -> branded.servingSize
            gramWeight != null && gramWeight > 0 -> gramWeight
            else -> 100.0
        }
        val factor = servingSizeGrams / 100.0

        return FoodItem(
            id = foodItemId(raw.fdcId),
            name = raw.description,
            brand = branded?.brandOwner?.takeIf { it.isNotBlank() } ?: branded?.brandName?.takeIf { it.isNotBlank() },
            servingSize = servingSizeGrams,
            servingUnit = "g",
            servingSizeGrams = servingSizeGrams,
            calories = caloriesPer100g * factor,
            carbsG = (nutrientsPer100g[NUTRIENT_CARBS] ?: 0.0) * factor,
            proteinG = (nutrientsPer100g[NUTRIENT_PROTEIN] ?: 0.0) * factor,
            fatG = (nutrientsPer100g[NUTRIENT_FAT] ?: 0.0) * factor,
            fiberG = nutrientsPer100g[NUTRIENT_FIBER]?.let { it * factor },
            sodiumMg = nutrientsPer100g[NUTRIENT_SODIUM]?.let { it * factor },
            source = source,
            fdcId = raw.fdcId,
            createdAt = DateUtil.now()
        )
    }

    private fun parseFood(reader: BufferedReader, includeBranded: Boolean): Map<Long, RawFood> {
        val header = reader.readLine() ?: return emptyMap()
        val cols = Csv.parseLine(header)
        val idIdx = cols.indexOf("fdc_id")
        val descIdx = cols.indexOf("description")
        val typeIdx = cols.indexOf("data_type")
        val out = LinkedHashMap<Long, RawFood>()
        reader.lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            val fields = Csv.parseLine(line)
            val dataType = fields.getOrNull(typeIdx) ?: return@forEach
            val wanted = dataType == "foundation_food" || dataType == "sr_legacy_food" ||
                (includeBranded && dataType == "branded_food")
            if (!wanted) return@forEach
            val fdcId = fields.getOrNull(idIdx)?.toLongOrNull() ?: return@forEach
            out[fdcId] = RawFood(fdcId, fields.getOrNull(descIdx).orEmpty(), dataType)
        }
        return out
    }

    private fun parseFoodNutrients(reader: BufferedReader, wantedIds: Set<Long>): Map<Long, Map<Long, Double>> {
        val header = reader.readLine() ?: return emptyMap()
        val cols = Csv.parseLine(header)
        val fdcIdx = cols.indexOf("fdc_id")
        val nutrientIdx = cols.indexOf("nutrient_id")
        val amountIdx = cols.indexOf("amount")
        val out = HashMap<Long, MutableMap<Long, Double>>()
        reader.lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            val fields = Csv.parseLine(line)
            val fdcId = fields.getOrNull(fdcIdx)?.toLongOrNull() ?: return@forEach
            if (fdcId !in wantedIds) return@forEach
            val nutrientId = fields.getOrNull(nutrientIdx)?.toLongOrNull() ?: return@forEach
            val amount = fields.getOrNull(amountIdx)?.toDoubleOrNull() ?: return@forEach
            out.getOrPut(fdcId) { mutableMapOf() }[nutrientId] = amount
        }
        return out
    }

    private fun parsePortions(reader: BufferedReader, wantedIds: Set<Long>): Map<Long, Double> {
        val header = reader.readLine() ?: return emptyMap()
        val cols = Csv.parseLine(header)
        val fdcIdx = cols.indexOf("fdc_id")
        val gramIdx = cols.indexOf("gram_weight")
        val seqIdx = cols.indexOf("seq_num")
        // Lowest seq_num wins — that's USDA's "primary" portion for the food.
        val best = HashMap<Long, Pair<Int, Double>>()
        reader.lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            val fields = Csv.parseLine(line)
            val fdcId = fields.getOrNull(fdcIdx)?.toLongOrNull() ?: return@forEach
            if (fdcId !in wantedIds) return@forEach
            val gramWeight = fields.getOrNull(gramIdx)?.toDoubleOrNull() ?: return@forEach
            val seq = fields.getOrNull(seqIdx)?.toIntOrNull() ?: Int.MAX_VALUE
            val existing = best[fdcId]
            if (existing == null || seq < existing.first) best[fdcId] = seq to gramWeight
        }
        return best.mapValues { it.value.second }
    }

    private fun parseBranded(reader: BufferedReader, wantedIds: Set<Long>): Map<Long, BrandedInfo> {
        val header = reader.readLine() ?: return emptyMap()
        val cols = Csv.parseLine(header)
        val fdcIdx = cols.indexOf("fdc_id")
        val ownerIdx = cols.indexOf("brand_owner")
        val nameIdx = cols.indexOf("brand_name")
        val sizeIdx = cols.indexOf("serving_size")
        val unitIdx = cols.indexOf("serving_size_unit")
        val out = HashMap<Long, BrandedInfo>()
        reader.lineSequence().forEach { line ->
            if (line.isBlank()) return@forEach
            val fields = Csv.parseLine(line)
            val fdcId = fields.getOrNull(fdcIdx)?.toLongOrNull() ?: return@forEach
            if (fdcId !in wantedIds) return@forEach
            out[fdcId] = BrandedInfo(
                brandOwner = fields.getOrNull(ownerIdx),
                brandName = fields.getOrNull(nameIdx),
                servingSize = fields.getOrNull(sizeIdx)?.toDoubleOrNull(),
                servingSizeUnit = fields.getOrNull(unitIdx)
            )
        }
        return out
    }
}
