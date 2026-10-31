package com.mango.domain

import com.mango.data.Food
import com.mango.data.FoodCategory
import com.mango.data.MealSlot
import com.mango.data.MealStatus
import com.mango.data.MealType
import com.mango.data.PantryItem
import com.mango.data.Recipe
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/** A pantry lot expressed by food name, as the planner sees it. */
data class StockItem(val food: String, val grams: Double, val expiresOn: Long, val isProduce: Boolean)

data class ShoppingItem(val food: Food, val grams: Double)

data class ScoreBreakdown(val pantry: Double, val expiring: Double, val season: Double, val macro: Double, val total: Double)

data class PlanRequest(
    val recipes: List<Recipe>,
    val foods: Map<String, Food>,
    val pantry: List<StockItem>,
    val today: Long,
    val dislikes: List<String>,
    val southernHemisphere: Boolean = false,
    /** Zero-waste boost after a skipped meal: wider expiry window and doubled expiry weight. */
    val produceBoost: Boolean = false,
    /** Slots that are kept as-is (history and the rest of the plan). */
    val existing: List<MealSlot> = emptyList(),
    val exclude: Set<Long> = emptySet(),
    val targetsFor: (Long) -> DailyTargets,
)

/**
 * Deterministic on-device recipe matcher.
 * Score = PantryMatch·40 + ExpiringUsed·30 + InSeason·20 + MacroProximity·10 (each term normalised 0..1).
 */
object MealPlanner {
    const val ANTI_REPEAT_DAYS = 3
    const val EXPIRY_WINDOW_DAYS = 3
    const val BOOST_EXPIRY_WINDOW_DAYS = 5

    // ---------- Dislikes ----------

    private fun tokens(s: String): List<String> =
        s.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }
            .map { if (it.length > 3 && it.endsWith("s") && !it.endsWith("ss")) it.dropLast(1) else it }

    /** Whole-word match on food name or any allergen group ("pea" does not match "peanut butter"). */
    fun matchesDislike(food: Food?, foodName: String, term: String): Boolean {
        val t = tokens(term)
        if (t.isEmpty()) return false
        if (tokens(foodName).containsAll(t)) return true
        return food?.groups.orEmpty().any { tokens(it).containsAll(t) }
    }

    fun isAllowed(recipe: Recipe, dislikes: List<String>, foods: Map<String, Food>): Boolean =
        dislikes.none { term ->
            val t = tokens(term)
            t.isNotEmpty() && (
                tokens(recipe.name).containsAll(t) ||
                    recipe.ingredients.any { matchesDislike(foods[it.food], it.food, term) }
                )
        }

    // ---------- Scoring ----------

    fun portionsFor(recipe: Recipe, mealType: MealType, targets: DailyTargets): Double {
        val kcal = recipe.perServing.kcal
        if (kcal <= 0) return 1.0
        val raw = targets.kcal * mealType.energyShare / kcal
        return ((raw * 4).roundToInt() / 4.0).coerceIn(0.5, 3.0)
    }

    fun seasonMonth(epochDay: Long, southern: Boolean): Int {
        val m = LocalDate.ofEpochDay(epochDay).monthValue
        return if (southern) (m - 1 + 6) % 12 + 1 else m
    }

    fun macroProximity(recipe: Recipe, targets: DailyTargets): Double {
        val n = recipe.perServing
        if (n.kcal <= 0) return 0.0
        val dev = abs(n.protein * 4 / n.kcal - targets.proteinShare) +
            abs(n.carbs * 4 / n.kcal - targets.carbShare) +
            abs(n.fat * 9 / n.kcal - targets.fatShare)
        return 1 - min(1.0, dev / 0.5)
    }

    fun score(
        recipe: Recipe,
        portions: Double,
        day: Long,
        stock: Stock,
        req: PlanRequest,
        targets: DailyTargets,
    ): ScoreBreakdown {
        val factor = portions / recipe.servings.coerceAtLeast(1)
        val window = if (req.produceBoost) BOOST_EXPIRY_WINDOW_DAYS else EXPIRY_WINDOW_DAYS
        var need = 0.0
        var covered = 0.0
        var expiring = 0.0
        var seasonal = 0
        var inSeason = 0
        val month = seasonMonth(day, req.southernHemisphere)
        for (ing in recipe.ingredients) {
            val g = ing.grams * factor
            need += g
            covered += min(g, stock.available(ing.food, day))
            val food = req.foods[ing.food]
            if (stock.hasExpiring(ing.food, day, req.today + window)) {
                expiring += if (req.produceBoost && food?.category == FoodCategory.PRODUCE) 2.0 else 1.0
            }
            if (food != null && food.category == FoodCategory.PRODUCE && food.seasonMonths.isNotEmpty()) {
                seasonal++
                if (month in food.seasonMonths) inSeason++
            }
        }
        val pantry = if (need > 0) covered / need else 0.0
        val exp = min(1.0, expiring / 3.0)
        val season = if (seasonal > 0) inSeason.toDouble() / seasonal else 0.0
        val macro = macroProximity(recipe, targets)
        val expWeight = if (req.produceBoost) 60.0 else 30.0
        return ScoreBreakdown(pantry, exp, season, macro, pantry * 40 + exp * expWeight + season * 20 + macro * 10)
    }

    /** Deterministic tie-breaker (< 1 point) so equal-scoring recipes rotate by day. */
    private fun variety(recipeId: Long, day: Long): Double =
        Math.floorMod(recipeId * 7919 + day * 104729, 100L) / 100.0

    // ---------- Planning ----------

    fun plan(toFill: List<Pair<Long, MealType>>, req: PlanRequest): List<MealSlot> {
        val stock = Stock(req.pantry)
        val byId = req.recipes.associateBy { it.id }
        val used = mutableMapOf<Long, MutableSet<Long>>()
        // Skipped meals don't count as eaten: their recipe may return so its ingredients get used.
        req.existing.filter { it.status != MealStatus.SKIPPED }
            .forEach { used.getOrPut(it.epochDay) { mutableSetOf() } += it.recipeId }
        req.existing.filter { it.status == MealStatus.PLANNED && it.epochDay >= req.today }
            .sortedWith(compareBy({ it.epochDay }, { it.mealType.ordinal }))
            .forEach { s -> byId[s.recipeId]?.let { stock.reserve(it, s.portions, s.epochDay) } }

        val allowed = req.recipes.filter {
            !it.isHidden && it.id !in req.exclude && it.perServing.kcal > 0 && isAllowed(it, req.dislikes, req.foods)
        }
        val result = mutableListOf<MealSlot>()
        for ((day, type) in toFill.distinct().sortedWith(compareBy({ it.first }, { it.second.ordinal }))) {
            val targets = req.targetsFor(day)
            val pool = allowed.filter { it.mealType == type }
            val recent = (day - ANTI_REPEAT_DAYS..day + ANTI_REPEAT_DAYS).flatMap { used[it].orEmpty() }.toSet()
            val candidates = pool.filter { it.id !in recent }
                .ifEmpty { pool.filter { it.id !in used[day].orEmpty() } }
                .ifEmpty { pool }
            if (candidates.isEmpty()) continue
            val best = candidates.map { r ->
                val portions = portionsFor(r, type, targets)
                Triple(r, portions, score(r, portions, day, stock, req, targets).total + variety(r.id, day))
            }.maxWith(compareBy<Triple<Recipe, Double, Double>> { it.third }.thenByDescending { it.first.id })
            stock.reserve(best.first, best.second, day)
            used.getOrPut(day) { mutableSetOf() } += best.first.id
            result += MealSlot(day, type, best.first.id, best.second)
        }
        return result
    }

    fun shoppingList(
        slots: List<MealSlot>,
        recipes: Map<Long, Recipe>,
        foods: Map<String, Food>,
        pantry: List<StockItem>,
        today: Long,
    ): List<ShoppingItem> {
        val need = mutableMapOf<String, Double>()
        slots.filter { it.status == MealStatus.PLANNED }.forEach { s ->
            val r = recipes[s.recipeId] ?: return@forEach
            val factor = s.portions / r.servings.coerceAtLeast(1)
            r.ingredients.forEach { need[it.food] = (need[it.food] ?: 0.0) + it.grams * factor }
        }
        val have = pantry.filter { it.expiresOn >= today }.groupBy { it.food }.mapValues { (_, v) -> v.sumOf { it.grams } }
        return need.mapNotNull { (name, grams) ->
            val missing = grams - (have[name] ?: 0.0)
            val food = foods[name]
            if (missing > 0.5 && food != null) ShoppingItem(food, ceil(missing / 10) * 10) else null
        }.sortedWith(compareBy({ it.food.category.ordinal }, { it.food.name }))
    }

    /**
     * First-expiry-first-out deduction of a cooked recipe from the pantry.
     * Returns the updated lots and the ids of lots that were used up.
     */
    fun consume(
        items: List<PantryItem>,
        foodIdByName: Map<String, Long>,
        recipe: Recipe,
        portions: Double,
    ): Pair<List<PantryItem>, List<Long>> {
        val factor = portions / recipe.servings.coerceAtLeast(1)
        val lots = items.sortedBy { it.expiresOnEpochDay }.associateBy { it.id }.toMutableMap()
        val changed = mutableSetOf<Long>()
        for (ing in recipe.ingredients) {
            val foodId = foodIdByName[ing.food] ?: continue
            var remaining = ing.grams * factor
            for (lot in lots.values.filter { it.foodId == foodId && it.grams > 0 }.sortedBy { it.expiresOnEpochDay }) {
                if (remaining <= 0) break
                val take = min(lot.grams, remaining)
                lots[lot.id] = lot.copy(grams = lot.grams - take)
                remaining -= take
                changed += lot.id
            }
        }
        val updated = changed.mapNotNull { lots[it] }
        return updated.filter { it.grams > 0.5 } to updated.filter { it.grams <= 0.5 }.map { it.id }
    }

    /** Mutable FEFO stock used while simulating a week of meals. */
    class Stock(items: List<StockItem>) {
        private class Lot(var grams: Double, val expiresOn: Long)

        private val lots: Map<String, MutableList<Lot>> =
            items.groupBy { it.food }.mapValues { (_, v) -> v.sortedBy { it.expiresOn }.map { Lot(it.grams, it.expiresOn) }.toMutableList() }

        fun available(food: String, day: Long): Double =
            lots[food].orEmpty().filter { it.expiresOn >= day }.sumOf { it.grams }

        fun hasExpiring(food: String, day: Long, until: Long): Boolean =
            lots[food].orEmpty().any { it.grams > 0 && it.expiresOn in day..until }

        fun reserve(recipe: Recipe, portions: Double, day: Long) {
            val factor = portions / recipe.servings.coerceAtLeast(1)
            for (ing in recipe.ingredients) {
                var remaining = ing.grams * factor
                for (lot in lots[ing.food].orEmpty()) {
                    if (remaining <= 0) break
                    if (lot.expiresOn < day) continue
                    val take = min(lot.grams, remaining)
                    lot.grams -= take
                    remaining -= take
                }
            }
        }
    }
}

/** Maps an Open Food Facts product name to likely generic catalog foods (offline token overlap). */
object FoodMatcher {
    private fun tokens(s: String) = s.lowercase().split(Regex("[^\\p{L}0-9]+"))
        .filter { it.length >= 3 }
        .map { if (it.length > 3 && it.endsWith("s")) it.dropLast(1) else it }
        .toSet()

    fun suggest(text: String, foods: List<Food>, limit: Int = 6): List<Food> {
        val t = tokens(text)
        if (t.isEmpty()) return emptyList()
        return foods.filter { !it.isUserAdded }.mapNotNull { f ->
            val ft = tokens(f.name)
            if (ft.isEmpty()) return@mapNotNull null
            val hit = ft.count { it in t }
            if (hit == 0) null else f to hit.toDouble() / ft.size + hit * 0.1
        }.sortedByDescending { it.second }.take(limit).map { it.first }
    }
}
