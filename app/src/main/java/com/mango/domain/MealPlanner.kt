package com.mango.domain

import com.mango.data.Food
import com.mango.data.FoodCategory
import com.mango.data.KitchenTool
import com.mango.data.MealSlot
import com.mango.data.MealStatus
import com.mango.data.MealType
import com.mango.data.PantryItem
import com.mango.data.Recipe
import com.mango.data.RecipeIngredient
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A pantry lot expressed by food name, as the planner sees it. Frozen lots (and birds not bought yet) only
 * become usable from [availableFrom].
 */
data class StockItem(
    val food: String,
    val grams: Double,
    val expiresOn: Long,
    val isProduce: Boolean,
    val availableFrom: Long = Long.MIN_VALUE,
    /** Counts as "use it up" like expiring food (the pieces of a whole bird, homemade stock in the freezer). */
    val priority: Boolean = false,
)

data class ShoppingItem(val food: Food, val grams: Double, val note: String? = null)

data class ScoreBreakdown(
    val pantry: Double,
    val expiring: Double,
    val season: Double,
    val macro: Double,
    val total: Double,
    val reuse: Double = 0.0,
    val ease: Double = 0.0,
)

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
    /** Weight of the "shorter shopping list" term: favours ingredients already in this week's basket. */
    val reuseWeight: Double = MealPlanner.REUSE_WEIGHT,
    /** Different breakfasts per week; once reached, breakfasts rotate. */
    val breakfastVariety: Int = 2,
    /** Breakfast recipes the user picked (empty = any). */
    val breakfastPicks: Set<Long> = emptySet(),
    /** Batch cooking: long recipes cover extra main meals in the following days as leftovers. */
    val batchCooking: Boolean = true,
    /** Appliances available: recipes needing one the user lacks are left out. */
    val kitchen: Set<KitchenTool> = emptySet(),
    /**
     * Foods in limited supply this week (the pieces of one bird): recipes needing more than what's left are
     * penalised, so the week's chicken matches the bird instead of calling for a second one.
     */
    val scarce: Set<String> = emptySet(),
    val targetsFor: (Long) -> DailyTargets,
)

/**
 * Deterministic on-device recipe matcher.
 * Score = PantryMatch·40 + ExpiringUsed·30 + InSeason·20 + MacroProximity·10 (each term normalised 0..1)
 *       + BasketReuse·[REUSE_WEIGHT]: share of a recipe's ingredients already bought for the week, so the
 *         plan converges on fewer different items (one kind of beans, one grain…) and less leftover waste.
 *       + Ease·[EASE_WEIGHT]: hands-on minutes per meal, where a batch recipe's prep is shared by every
 *         meal it covers, so a 40-minute curry eaten three times beats a 25-minute dish cooked once.
 * Hard rule: the same recipe is never eaten on two consecutive days (leftovers included).
 * Breakfasts rotate between [PlanRequest.breakfastVariety] recipes (from the user's picks); batch recipes
 * cover up to [Recipe.batchMeals] main meals: from the fridge on day +2, frozen for 3-meal recipes on days +4..+6.
 */
object MealPlanner {
    const val ANTI_REPEAT_DAYS = 3
    const val EXPIRY_WINDOW_DAYS = 3
    const val BOOST_EXPIRY_WINDOW_DAYS = 5
    const val REUSE_WEIGHT = 50.0
    const val EASE_WEIGHT = 25.0
    const val SCARCE_WEIGHT = 70.0
    /** Leftovers from the fridge are eaten 2 days after cooking (never the next day; USDA: 3-4 days max). */
    const val FRIDGE_LEFTOVER_DAY = 2
    /** Freezable batches (3-meal recipes: soups, stews, curries, sauces) can serve a frozen portion later in the week. */
    val FREEZER_DAYS = 4..6
    /** Longest span between cooking a batch and eating its last portion. */
    const val BATCH_WINDOW_DAYS = 6
    private val MAINS = setOf(MealType.LUNCH, MealType.DINNER)

    /** Foods that can stand in for a recipe ingredient and are used first: homemade stock instead of broth. */
    val ALIASES: Map<String, List<String>> = mapOf("chicken broth" to listOf(WholeChicken.STOCK))

    fun hasTools(recipe: Recipe, kitchen: Set<KitchenTool>): Boolean =
        recipe.needsAny.isEmpty() || recipe.needsAny.any { it in kitchen }

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
        basket: Set<String> = emptySet(),
        mealsCovered: Int = 1,
    ): ScoreBreakdown {
        val factor = portions / recipe.servings.coerceAtLeast(1)
        val window = if (req.produceBoost) BOOST_EXPIRY_WINDOW_DAYS else EXPIRY_WINDOW_DAYS
        var need = 0.0
        var covered = 0.0
        var expiring = 0.0
        var priorityHit = false
        var seasonal = 0
        var inSeason = 0
        var counted = 0
        var reused = 0
        val month = seasonMonth(day, req.southernHemisphere)
        // A breakfast is repeated through the week's rotation: its limited ingredients are needed every time.
        val repeats = if (recipe.mealType == MealType.BREAKFAST) ceil(7.0 / req.breakfastVariety.coerceAtLeast(1)) else 1.0
        var scarceNeed = 0.0
        var scarceMissing = 0.0
        for (ing in recipe.ingredients) {
            val g = ing.grams * factor
            need += g
            val avail = stock.available(ing.food, day)
            covered += min(g, avail)
            if (ing.food in req.scarce) {
                scarceNeed += g * repeats
                scarceMissing += (g * repeats - avail).coerceAtLeast(0.0)
            }
            val food = req.foods[ing.food]
            // Food to use up: expiring soon, or priority stock (a bird's pieces) covering at least half the need.
            if (stock.hasExpiring(ing.food, day, req.today + window)) {
                expiring += if (req.produceBoost && food?.category == FoodCategory.PRODUCE) 2.0 else 1.0
            } else if (!priorityHit && stock.priorityAvailable(ing.food, day) >= 0.5 * g * repeats) {
                // Priority stock (a bird's pieces) counts once per recipe, so one-pot and single-piece dishes compete evenly.
                priorityHit = true
                expiring += 1.0
            }
            // Spices last for months and are bought once, so they don't count towards basket variety.
            if (food?.category != FoodCategory.SPICE) {
                counted++
                if (basketKey(ing.food, req.foods) in basket) reused++
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
        val reuse = if (counted > 0 && basket.isNotEmpty()) reused.toDouble() / counted else 0.0
        val expWeight = if (req.produceBoost) 60.0 else 30.0
        val minutesPerMeal = recipe.prepMinutes.toDouble() / mealsCovered.coerceAtLeast(1)
        val ease = 1 - ((minutesPerMeal - 15) / 45).coerceIn(0.0, 1.0)
        val scarcity = if (scarceNeed > 0) scarceMissing / scarceNeed else 0.0
        val total = pantry * 40 + exp * expWeight + season * 20 + macro * 10 + reuse * req.reuseWeight + ease * EASE_WEIGHT - scarcity * SCARCE_WEIGHT
        return ScoreBreakdown(pantry, exp, season, macro, total, reuse, ease)
    }

    /**
     * Whether a planned meal still needs ingredients: leftovers don't once their batch has been cooked
     * (the cooking deducted the whole batch). Orphan leftovers, whose cooking slot is gone, are cooked fresh.
     */
    fun needsIngredients(slot: MealSlot, all: List<MealSlot>): Boolean {
        if (slot.status != MealStatus.PLANNED) return false
        if (!slot.isLeftover) return true
        val cook = all.firstOrNull { it.batchId == slot.batchId && !it.isLeftover }
        return cook == null || cook.status == MealStatus.PLANNED
    }

    /** Interchangeable foods share one basket key, so a recipe with black beans counts as reusing chickpeas. */
    private fun basketKey(food: String, foods: Map<String, Food>): String =
        foods[food]?.swapGroup?.let { "group:$it" } ?: food

    /** The ingredients actually cooked for a meal: recipe ingredients with the meal's substitutions applied. */
    fun effectiveIngredients(recipe: Recipe, swaps: Map<String, String>, foods: Map<String, Food> = emptyMap()): List<RecipeIngredient> =
        if (swaps.isEmpty()) {
            recipe.ingredients
        } else {
            recipe.ingredients.map { ing ->
                val target = swaps[ing.food] ?: return@map ing
                val from = foods[ing.food]
                val to = foods[target]
                // Carbs are swapped at equal energy (100 g dry rice ≈ 470 g potato); everything else gram for gram.
                val factor = if (from?.carbType != null && to?.carbType != null && to.kcal > 0) from.kcal / to.kcal else 1.0
                RecipeIngredient(target, ing.grams * factor)
            }
                .groupBy { it.food }
                .map { (food, list) -> RecipeIngredient(food, list.sumOf { it.grams }) }
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
        val basket = mutableSetOf<String>()
        req.existing.filter { it.epochDay >= req.today && needsIngredients(it, req.existing) }
            .sortedWith(compareBy({ it.epochDay }, { it.mealType.ordinal }))
            .forEach { s ->
                byId[s.recipeId]?.let { r ->
                    stock.reserve(r, s.portions, s.epochDay, s.swaps)
                    basket += effectiveIngredients(r, s.swaps).map { basketKey(it.food, req.foods) }
                }
            }

        val allowed = req.recipes.filter {
            !it.isHidden && it.id !in req.exclude && it.perServing.kcal > 0 && hasTools(it, req.kitchen) && isAllowed(it, req.dislikes, req.foods)
        }
        val allowedIds = allowed.map { it.id }.toSet()
        val queue = toFill.distinct().sortedWith(compareBy({ it.first }, { it.second.ordinal }))
        val free = queue.toMutableSet()
        val result = mutableListOf<MealSlot>()

        // Breakfast rotation: breakfasts already on this week's menu belong to the rotation.
        val rotation = LinkedHashSet<Long>()
        val lastBreakfast = mutableMapOf<Long, Long>()
        req.existing.filter { it.mealType == MealType.BREAKFAST && it.status != MealStatus.SKIPPED && it.epochDay in req.today - 6..req.today + 6 }
            .sortedBy { it.epochDay }
            .forEach {
                if (it.recipeId in allowedIds && (req.breakfastPicks.isEmpty() || it.recipeId in req.breakfastPicks)) rotation += it.recipeId
                lastBreakfast[it.recipeId] = maxOf(lastBreakfast[it.recipeId] ?: Long.MIN_VALUE, it.epochDay)
            }

        fun take(r: Recipe, day: Long, type: MealType, batchId: Long = 0, leftover: Boolean = false): MealSlot {
            val slot = MealSlot(day, type, r.id, portionsFor(r, type, req.targetsFor(day)), batchId = batchId, isLeftover = leftover)
            stock.reserve(r, slot.portions, day)
            basket += r.ingredients.map { basketKey(it.food, req.foods) }
            used.getOrPut(day) { mutableSetOf() } += r.id
            free -= day to type
            result += slot
            return slot
        }

        /**
         * Meals one batch covers. A dish built on a limited supply (a whole bird) covers only as many meals as
         * the bird serves at this person's portion; other batch recipes scale freely.
         */
        fun batchSize(r: Recipe, type: MealType, targets: DailyTargets): Int {
            if (req.scarce.isEmpty() || r.ingredients.none { it.food in req.scarce }) return r.batchMeals
            val portion = portionsFor(r, type, targets)
            return (r.servings / portion + 0.25).toInt().coerceIn(1, r.batchMeals)
        }

        /** Same recipe on the given day or a neighbouring one: forbidden (no repeats on consecutive days). */
        fun nearby(id: Long, d: Long, extra: Collection<Long> = emptyList()): Boolean =
            (d - 1..d + 1).any { id in used[it].orEmpty() || it in extra }

        /** Slots for leftovers: day +2 from the fridge, then (freezable recipes) a frozen portion on days +4..+6. */
        fun leftoverSlots(r: Recipe, day: Long, type: MealType, count: Int): List<Pair<Long, MealType>> {
            val out = mutableListOf<Pair<Long, MealType>>()
            var last = type
            val days = listOf(day + FRIDGE_LEFTOVER_DAY) + if (r.batchMeals >= 3) FREEZER_DAYS.map { day + it } else emptyList()
            for (d in days) {
                if (out.size >= count) break
                if (nearby(r.id, d, extra = listOf(day) + out.map { it.first })) continue
                val order = if (last == MealType.DINNER) listOf(MealType.LUNCH, MealType.DINNER) else listOf(MealType.DINNER, MealType.LUNCH)
                order.firstOrNull { (d to it) in free }?.let { out += d to it; last = it }
            }
            return out
        }

        for ((day, type) in queue) {
            if ((day to type) !in free) continue // already covered by a batch
            if (type == MealType.BREAKFAST && rotation.size >= req.breakfastVariety) {
                val next = rotation.mapNotNull { byId[it] }.filter { it.id in allowedIds && !nearby(it.id, day) }
                    .minByOrNull { lastBreakfast[it.id] ?: Long.MIN_VALUE }
                if (next != null) {
                    take(next, day, type)
                    lastBreakfast[next.id] = day
                    continue
                }
            }
            val targets = req.targetsFor(day)
            val pool = allowed.filter { it.mealType == type }.let { all ->
                if (type == MealType.BREAKFAST && req.breakfastPicks.isNotEmpty()) all.filter { it.id in req.breakfastPicks }.ifEmpty { all } else all
            }
            val recent = (day - ANTI_REPEAT_DAYS..day + ANTI_REPEAT_DAYS).flatMap { used[it].orEmpty() }.toSet()
            // Never the same dish on consecutive days; with a bigger choice, not within 3 days either.
            val notNearby = pool.filter { !nearby(it.id, day) }
            val candidates = notNearby.filter { it.id !in recent }
                .ifEmpty { notNearby }
                .ifEmpty { allowed.filter { it.mealType == type && !nearby(it.id, day) } }
                .ifEmpty { pool }
            if (candidates.isEmpty()) continue
            val best = candidates.map { r ->
                val covered = if (req.batchCooking && type in MAINS && r.batchMeals > 1) 1 + leftoverSlots(r, day, type, batchSize(r, type, targets) - 1).size else 1
                val portions = portionsFor(r, type, targets)
                r to score(r, portions, day, stock, req, targets, basket, covered).total + variety(r.id, day)
            }.maxWith(compareBy<Pair<Recipe, Double>> { it.second }.thenByDescending { it.first.id }).first
            val extra = if (req.batchCooking && type in MAINS && best.batchMeals > 1) leftoverSlots(best, day, type, batchSize(best, type, targets) - 1) else emptyList()
            val batchId = if (extra.isEmpty()) 0L else day * 10 + type.ordinal + 1
            take(best, day, type, batchId)
            extra.forEach { (d, t) -> take(best, d, t, batchId, leftover = true) }
            if (type == MealType.BREAKFAST) {
                rotation += best.id
                lastBreakfast[best.id] = day
            }
        }
        return result
    }

    /**
     * Shorter shopping list: for each group of interchangeable foods (beans, berries, nuts…) used by the
     * week's planned meals, keep ONE member – preferring what is already in the pantry, then the most used –
     * and record substitutions on the other meals. Disliked foods are never chosen.
     * Returns the planned slots whose substitutions changed.
     */
    fun consolidate(
        slots: List<MealSlot>,
        recipes: Map<Long, Recipe>,
        foods: Map<String, Food>,
        pantry: List<StockItem>,
        dislikes: List<String>,
        baseSwaps: (Recipe) -> Map<String, String> = { emptyMap() },
    ): List<MealSlot> {
        val planned = slots.filter { it.status == MealStatus.PLANNED && it.recipeId in recipes }
        val needed = mutableMapOf<String, Double>()
        planned.forEach { s ->
            val r = recipes.getValue(s.recipeId)
            effectiveIngredients(r, baseSwaps(r), foods)
                .forEach { needed[it.food] = (needed[it.food] ?: 0.0) + it.grams * s.portions / r.servings.coerceAtLeast(1) }
        }
        val inPantry = pantry.groupBy { it.food }.mapValues { (_, v) -> v.sumOf { it.grams } }
        val canonical = needed.keys
            .filter { foods[it]?.swapGroup != null }
            .groupBy { foods.getValue(it).swapGroup!! }
            .filterValues { it.size > 1 }
            .mapValues { (_, members) ->
                members.filter { m -> dislikes.none { matchesDislike(foods[m], m, it) } }
                    .maxWithOrNull(compareBy<String>({ inPantry[it] ?: 0.0 }, { needed[it] ?: 0.0 }).thenByDescending { it })
            }
        return planned.mapNotNull { s ->
            val r = recipes.getValue(s.recipeId)
            val base = baseSwaps(r)
            val swaps = r.ingredients.mapNotNull { ing ->
                val used = base[ing.food] ?: ing.food
                val target = foods[used]?.swapGroup?.let { canonical[it] } ?: used
                if (target != ing.food) ing.food to target else null
            }.toMap()
            if (swaps != s.swaps) s.copy(swaps = swaps) else null
        }
    }

    fun shoppingList(
        slots: List<MealSlot>,
        recipes: Map<Long, Recipe>,
        foods: Map<String, Food>,
        pantry: List<StockItem>,
        today: Long,
        alwaysStocked: Set<String> = emptySet(),
    ): List<ShoppingItem> {
        val need = mutableMapOf<String, Double>()
        slots.filter { it.epochDay >= today && needsIngredients(it, slots) }.forEach { s ->
            val r = recipes[s.recipeId] ?: return@forEach
            val factor = s.portions / r.servings.coerceAtLeast(1)
            effectiveIngredients(r, s.swaps, foods).forEach { need[it.food] = (need[it.food] ?: 0.0) + it.grams * factor }
        }
        val have = pantry.filter { it.expiresOn >= today }.groupBy { it.food }.mapValues { (_, v) -> v.sumOf { it.grams } }
        return need.mapNotNull { (name, grams) ->
            val missing = grams - (listOf(name) + ALIASES[name].orEmpty()).sumOf { have[it] ?: 0.0 }
            val food = foods[name]
            if (missing > 0.5 && food != null && name !in alwaysStocked) ShoppingItem(food, ceil(missing / 10) * 10) else null
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
        swaps: Map<String, String> = emptyMap(),
        foods: Map<String, Food> = emptyMap(),
    ): Pair<List<PantryItem>, List<Long>> {
        val factor = portions / recipe.servings.coerceAtLeast(1)
        val lots = items.sortedBy { it.expiresOnEpochDay }.associateBy { it.id }.toMutableMap()
        val changed = mutableSetOf<Long>()
        for (ing in effectiveIngredients(recipe, swaps, foods)) {
            // Stand-ins first (homemade stock before shop-bought broth), then the food itself.
            val ids = (ALIASES[ing.food].orEmpty() + ing.food).mapNotNull { foodIdByName[it] }
            if (ids.isEmpty()) continue
            var remaining = ing.grams * factor
            val candidates = ids.flatMap { id -> lots.values.filter { it.foodId == id && it.grams > 0 }.sortedBy { it.expiresOnEpochDay } }
            for (lot in candidates) {
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
        private class Lot(var grams: Double, val expiresOn: Long, val availableFrom: Long, val priority: Boolean) {
            fun usable(day: Long) = expiresOn >= day && availableFrom <= day
        }

        private val lots: Map<String, MutableList<Lot>> =
            items.groupBy { it.food }.mapValues { (_, v) -> v.sortedBy { it.expiresOn }.map { Lot(it.grams, it.expiresOn, it.availableFrom, it.priority) }.toMutableList() }

        /** The food's own lots and its stand-ins (stand-ins first). */
        private fun lotsFor(food: String): List<Lot> = ALIASES[food].orEmpty().flatMap { lots[it].orEmpty() } + lots[food].orEmpty()

        fun available(food: String, day: Long): Double =
            lotsFor(food).filter { it.usable(day) }.sumOf { it.grams }

        fun hasExpiring(food: String, day: Long, until: Long): Boolean =
            lotsFor(food).any { it.grams > 0 && it.usable(day) && !it.priority && it.expiresOn in day..until }

        fun priorityAvailable(food: String, day: Long): Double =
            lotsFor(food).filter { it.priority && it.usable(day) }.sumOf { it.grams }

        fun reserve(recipe: Recipe, portions: Double, day: Long, swaps: Map<String, String> = emptyMap()) {
            val factor = portions / recipe.servings.coerceAtLeast(1)
            for (ing in effectiveIngredients(recipe, swaps)) {
                var remaining = ing.grams * factor
                for (lot in lotsFor(ing.food)) {
                    if (remaining <= 0) break
                    if (!lot.usable(day)) continue
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

/** Autocomplete for the dislikes / allergies field: allergen groups and catalog foods. */
object DislikeSuggestions {
    data class Suggestion(val term: String, val foodCount: Int, val isGroup: Boolean)

    /** Shown before typing: the most common allergens and dislikes. */
    val COMMON = listOf("gluten", "dairy", "egg", "nuts", "peanut", "soy", "fish", "shellfish", "sesame", "pork", "mushroom", "cilantro")

    fun suggest(query: String, foods: List<Food>, already: Collection<String>, limit: Int = 8): List<Suggestion> {
        val chosen = already.map { it.lowercase() }.toSet()
        val groupCounts = foods.flatMap { f -> f.groups.map { it to f.name } }.groupBy({ it.first }, { it.second }).mapValues { it.value.distinct().size }
        val q = query.trim().lowercase()
        if (q.isEmpty()) {
            return COMMON.filter { it !in chosen }.map { Suggestion(it, groupCounts[it] ?: 1, it in groupCounts) }.take(limit)
        }
        fun rank(term: String): Int? = when {
            term.startsWith(q) -> 0
            term.split(' ').any { it.startsWith(q) } -> 1
            q in term -> 2
            else -> null
        }
        val groups = groupCounts.keys.mapNotNull { g -> rank(g)?.let { Suggestion(g, groupCounts.getValue(g), true) to it } }
        val names = foods.map { it.name }.distinct().mapNotNull { n -> rank(n)?.let { Suggestion(n, 1, false) to it } }
        return (groups + names)
            .filter { it.first.term !in chosen }
            .sortedWith(compareBy({ it.second }, { !it.first.isGroup }, { it.first.term.length }))
            .map { it.first }
            .distinctBy { it.term }
            .take(limit)
    }
}
