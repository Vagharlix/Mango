package com.mango.domain

import com.mango.data.Food
import com.mango.data.FoodCategory
import com.mango.data.KitchenTool
import com.mango.data.MealSlot
import com.mango.data.Recipe
import com.mango.data.WholeChickenMode
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Buying whole chickens and breaking them down (10 pieces, Andy Cooks style: legs split into thighs and
 * drumsticks, wings, breasts) instead of buying parts. The frame (back, neck, wing tips, thigh bones) makes stock.
 *
 * Parts are counted as pieces – a thigh is a thigh – so weights are only estimates from the bird's size:
 * shares from poultry yield data (Univ. of Wisconsin Extension; broiler carcass studies), boneless fillets and
 * thighs, bone-in drumsticks and wings.
 */
object WholeChicken {
    const val WHOLE = "whole chicken"
    const val BREAST = "chicken breast"
    const val THIGH = "chicken thigh"
    const val DRUMSTICK = "chicken drumstick"
    const val WINGS = "chicken wings"
    const val FRAME = "chicken frame"
    const val STOCK = "homemade chicken stock"

    data class Part(val food: String, val pieces: Int, val share: Double, val singular: String, val plural: String) {
        fun name(count: Int) = "$count " + if (count == 1) singular else plural
    }

    val PARTS = listOf(
        Part(BREAST, 2, 0.23, "breast fillet", "breast fillets"),
        Part(THIGH, 2, 0.125, "thigh", "thighs"),
        Part(DRUMSTICK, 2, 0.125, "drumstick", "drumsticks"),
        Part(WINGS, 2, 0.10, "wing", "wings"),
        Part(FRAME, 1, 0.25, "frame for stock", "frames for stock"),
    )
    val PART_FOODS: Set<String> = PARTS.map { it.food }.toSet()
    private val MEAT = setOf(BREAST, THIGH, DRUMSTICK)
    private val byFood = PARTS.associateBy { it.food }

    /** "When it fits": the week's meals must use at least this share of the bird's meat. */
    const val FIT_SHARE = 0.5

    /** Up to this much more than a bird's worth still counts as one bird (a breast is a breast). */
    const val TOLERANCE = 0.3

    /** Quick stock: about 2 ml per gram of frame (Andy Cooks: one frame + wings gives ~800 ml). */
    const val STOCK_ML_PER_GRAM = 2.0

    /** Raw chicken keeps 1-2 days in the fridge (FoodSafety.gov): pieces eaten today or tomorrow stay there. */
    const val FRIDGE_USE_DAYS = 1

    fun part(food: String): Part? = byFood[food]
    fun partGrams(part: Part, kg: Double) = kg * 1000 * part.share
    fun pieceGrams(part: Part, kg: Double) = partGrams(part, kg) / part.pieces
    fun meatGrams(kg: Double) = PARTS.filter { it.food in MEAT }.sumOf { partGrams(it, kg) }

    /** Pieces in [grams] of a part, never less than one for a non-empty amount. */
    fun pieces(food: String, grams: Double, kg: Double): Int {
        val p = byFood[food] ?: return 0
        return max(if (grams > 0.5) 1 else 0, (grams / pieceGrams(p, kg)).roundToInt())
    }

    /** "2 breast fillets, 2 thighs, 2 drumsticks, 2 wings and 1 frame for stock". */
    fun describe(birds: Int): String =
        PARTS.map { it.name(it.pieces * birds) }.let { it.dropLast(1).joinToString(", ") + " and " + it.last() }

    /** A bird not bought yet, as stock the planner may build the week around (what isn't eaten fresh is frozen). */
    fun virtualStock(kg: Double, from: Long, until: Long): List<StockItem> =
        PARTS.map { StockItem(it.food, partGrams(it, kg), until, isProduce = false, availableFrom = from, priority = true) }

    /** Grams of each food the planned meals still need, per day (portion-scaled, substitutions applied). */
    fun demand(slots: List<MealSlot>, recipes: Map<Long, Recipe>, foods: Map<String, Food>, days: LongRange): Map<String, List<Pair<Long, Double>>> {
        val out = mutableMapOf<String, MutableList<Pair<Long, Double>>>()
        // A batch is cooked in one go: its leftover meals need their ingredients on the cooking day.
        val cookDay = slots.filter { it.batchId != 0L && !it.isLeftover }.associate { it.batchId to it.epochDay }
        slots.filter { MealPlanner.needsIngredients(it, slots) }.forEach { s ->
            val day = if (s.isLeftover) cookDay[s.batchId] ?: s.epochDay else s.epochDay
            if (day !in days) return@forEach
            val r = recipes[s.recipeId] ?: return@forEach
            val factor = s.portions / r.servings.coerceAtLeast(1)
            MealPlanner.effectiveIngredients(r, s.swaps, foods).forEach { out.getOrPut(it.food) { mutableListOf() } += day to it.grams * factor }
        }
        return out.mapValues { (_, v) -> v.sortedBy { it.first } }
    }

    /** Bird meat the plan uses over [days]. */
    fun meatUsed(slots: List<MealSlot>, recipes: Map<Long, Recipe>, foods: Map<String, Food>, days: LongRange): Double =
        demand(slots, recipes, foods, days).filterKeys { it in MEAT }.values.sumOf { list -> list.sumOf { it.second } }

    fun fits(meatUsed: Double, kg: Double) = meatUsed >= FIT_SHARE * meatGrams(kg)

    /** Whether to plan around a not-yet-bought bird this week. */
    fun wantsVirtualBird(mode: WholeChickenMode, boughtThisWeek: Boolean, partsInStock: Map<String, Double>, kg: Double): Boolean {
        if (mode == WholeChickenMode.OFF || boughtThisWeek) return false
        val meatInStock = partsInStock.filterKeys { it in MEAT }.values.sum()
        return meatInStock < 0.25 * meatGrams(kg)
    }

    /**
     * Birds to buy for the missing parts: enough for the part needed least (a little over a bird's worth still
     * counts as one); anything a dish needs beyond that is bought as extra pieces (see [extraPieces]).
     */
    fun birdsFor(missing: Map<String, Double>, kg: Double): Int {
        val ratios = PARTS.mapNotNull { p -> ((missing[p.food] ?: 0.0) / partGrams(p, kg)).takeIf { it > 0.05 } }
        return if (ratios.isEmpty()) 0 else max(1, ceil(ratios.min() - TOLERANCE).toInt())
    }

    /** Pieces still missing once [birds] are broken down, e.g. one more breast fillet. */
    fun extraPieces(missing: Map<String, Double>, birds: Int, kg: Double): Map<String, Int> =
        PARTS.associate { p ->
            val short = (missing[p.food] ?: 0.0) - birds * partGrams(p, kg)
            p.food to if (short <= 0) 0 else max(0, ceil(short / pieceGrams(p, kg) - TOLERANCE).toInt())
        }.filterValues { it > 0 }

    /**
     * Replaces the week's chicken-part lines with whole birds (and says what they break down into), when the
     * mode asks for it: always, or when the missing meat is at least half a bird.
     */
    fun toBirds(items: List<ShoppingItem>, mode: WholeChickenMode, kg: Double, whole: Food?): List<ShoppingItem> {
        if (mode == WholeChickenMode.OFF || whole == null) return items
        val missing = items.filter { it.food.name in PART_FOODS }.associate { it.food.name to it.grams }
        val meatMissing = missing.filterKeys { it in MEAT }.values.sum()
        if (meatMissing <= 0) return items
        if (mode == WholeChickenMode.WHEN_IT_FITS && meatMissing < FIT_SHARE * meatGrams(kg)) return items
        val birds = birdsFor(missing, kg)
        val extras = extraPieces(missing, birds, kg).mapNotNull { (food, n) ->
            val part = byFood.getValue(food)
            items.firstOrNull { it.food.name == food }?.let { ShoppingItem(it.food, n * pieceGrams(part, kg), note = "on top of the bird: " + part.name(n)) }
        }
        val bird = ShoppingItem(
            food = whole.copy(unitGrams = kg * 1000, unitName = if (birds == 1) "bird" else "birds"),
            grams = birds * kg * 1000,
            note = "~${kg.fmtKg()} each · breaks down into " + describe(birds),
        )
        return (items.filter { it.food.name !in PART_FOODS } + bird + extras).sortedWith(compareBy({ it.food.category.ordinal }, { it.food.name }))
    }

    private fun Double.fmtKg(): String = (if (this % 1.0 == 0.0) "%.0f" else "%.1f").format(this) + " kg"

    /** A pantry lot to create from bought birds. */
    data class Lot(val food: String, val grams: Double, val frozen: Boolean, val pieces: Int)

    /**
     * Where each piece goes after the breakdown: pieces the plan eats today or tomorrow stay in the fridge,
     * the rest go straight into the freezer. The frame stays in the fridge for today's stock.
     * [demand] is the plan's need not already covered by other fresh stock.
     */
    fun allocate(birds: Int, kg: Double, today: Long, demand: Map<String, List<Pair<Long, Double>>>): List<Lot> =
        PARTS.flatMap { part ->
            val pieces = part.pieces * birds
            val g = pieceGrams(part, kg)
            val soon = demand[part.food].orEmpty().filter { it.first <= today + FRIDGE_USE_DAYS }.sumOf { it.second }
            val fresh = when {
                part.food == FRAME -> pieces
                soon <= 0.5 -> 0
                else -> min(pieces, max(1, ceil(soon / g - TOLERANCE).toInt()))
            }
            listOfNotNull(
                Lot(part.food, fresh * g, frozen = false, pieces = fresh).takeIf { fresh > 0 },
                Lot(part.food, (pieces - fresh) * g, frozen = true, pieces = pieces - fresh).takeIf { pieces > fresh },
            )
        }

    /** Days a food keeps in the freezer for best quality (FoodSafety.gov cold storage chart; safe indefinitely at -18 °C). */
    fun freezerDays(food: Food): Int = when {
        food.name == STOCK || food.name.endsWith("broth") -> 90
        "mince" in food.name -> 105
        "poultry" in food.groups -> 270
        food.name in setOf("salmon fillet", "mackerel fillet") -> 75
        food.name == "cod fillet" -> 180
        food.category == FoodCategory.PROTEIN -> 120
        else -> 90
    }

    /** Foods worth freezing rather than throwing away when they're about to expire. */
    fun freezable(food: Food): Boolean =
        food.name == FRAME || food.name == STOCK ||
            (food.category == FoodCategory.PROTEIN && food.shelfLifeDays <= 7 && food.name !in setOf("eggs", "egg whites", "firm tofu"))
}

/**
 * The kitchen jobs of the day, derived from the pantry and the plan: break down a new bird, turn its frame into
 * stock, thaw what tomorrow's meals need, freeze what would otherwise go off.
 */
object KitchenPrep {
    data class PantryLot(val id: Long, val food: Food, val grams: Double, val expiresOn: Long, val frozen: Boolean, val addedOn: Long)

    /** Moving [grams] of a lot between fridge and freezer. */
    data class Move(val lotId: Long, val food: Food, val grams: Double, val label: String, val reason: String)

    sealed interface Task

    /** A bird bought today: how to break it down and where each piece goes. */
    data class Breakdown(val birds: Int, val fridge: List<String>, val freezer: List<String>) : Task

    data class MakeStock(val lotIds: List<Long>, val frameGrams: Double, val yieldMl: Int, val pressureCooker: Boolean, val framesFrozen: Int) : Task

    data class Thaw(val items: List<Move>) : Task

    data class Freeze(val items: List<Move>) : Task

    fun label(food: Food, grams: Double, kg: Double): String {
        val part = WholeChicken.part(food.name)
        return if (part != null) part.name(WholeChicken.pieces(food.name, grams, kg)) else food.name + " " + grams.roundToInt() + " g"
    }

    fun tasks(
        today: Long,
        lots: List<PantryLot>,
        demand: Map<String, List<Pair<Long, Double>>>,
        breakdownOn: Long,
        kg: Double,
        kitchen: Collection<KitchenTool>,
        mealLabel: (Long) -> String,
    ): List<Task> {
        val out = mutableListOf<Task>()
        // Fresh stock covers needs first (earliest expiry first), so only shortfalls come from the freezer.
        val freshLeft = lots.filter { !it.frozen && it.expiresOn >= today }.groupBy { it.food.name }
            .mapValues { (_, v) -> v.sortedBy { it.expiresOn }.map { it.expiresOn to it.grams }.toMutableList() }
        fun takeFresh(food: String, day: Long, grams: Double): Double {
            var left = grams
            for (i in freshLeft[food].orEmpty().indices) {
                val (exp, g) = freshLeft.getValue(food)[i]
                if (exp < day || g <= 0) continue
                val t = min(g, left)
                freshLeft.getValue(food)[i] = exp to g - t
                left -= t
                if (left <= 0) break
            }
            return left
        }

        if (breakdownOn != 0L) {
            val bird = lots.filter { it.food.name in WholeChicken.PART_FOODS && it.addedOn >= breakdownOn }
            if (bird.isNotEmpty()) {
                val frame = WholeChicken.part(WholeChicken.FRAME)!!
                val birds = max(1, (bird.filter { it.food.name == WholeChicken.FRAME }.sumOf { it.grams } / WholeChicken.pieceGrams(frame, kg)).roundToInt())
                fun list(frozen: Boolean) = bird.filter { it.frozen == frozen }.map { label(it.food, it.grams, kg) }
                out += Breakdown(birds, list(false), list(true))
            }
        }

        // Thaw: what today's and tomorrow's meals need beyond fresh stock. Homemade stock stands in for broth.
        val thaw = mutableListOf<Move>()
        val frozenLeft = lots.filter { it.frozen }.associate { it.id to it.grams }.toMutableMap()
        for (day in today..today + 1) {
            demand.forEach { (food, needs) ->
                val grams = needs.filter { it.first == day }.sumOf { it.second }
                if (grams <= 0) return@forEach
                val names = MealPlanner.ALIASES[food].orEmpty() + food
                var short = grams
                names.forEach { n -> if (short > 0) short = takeFresh(n, day, short) }
                if (short <= 0.5) return@forEach
                for (lot in lots.filter { it.frozen && it.food.name in names }.sortedBy { it.expiresOn }) {
                    if (short <= 0.5) break
                    val avail = frozenLeft[lot.id] ?: 0.0
                    if (avail <= 0.5) continue
                    // A thigh is a thigh: thaw whole pieces.
                    val part = WholeChicken.part(lot.food.name)
                    val g = if (part != null) min(avail, WholeChicken.pieces(lot.food.name, short, kg) * WholeChicken.pieceGrams(part, kg)) else min(avail, short)
                    frozenLeft[lot.id] = avail - g
                    short -= g
                    thaw += Move(lot.id, lot.food, g, label(lot.food, g, kg), if (day == today) "for today – thaw now" else "for " + mealLabel(day))
                }
            }
        }
        if (thaw.isNotEmpty()) out += Thaw(thaw)

        // Stock: fresh frames the plan doesn't cook itself in the next couple of days.
        val frames = lots.filter { it.food.name == WholeChicken.FRAME && !it.frozen }
        val frameNeed = demand[WholeChicken.FRAME].orEmpty().filter { it.first <= today + WholeChicken.FRIDGE_USE_DAYS }.sumOf { it.second }
        val spare = frames.sumOf { it.grams } - frameNeed
        if (frames.isNotEmpty() && spare > 50) {
            out += MakeStock(
                lotIds = frames.map { it.id },
                frameGrams = spare,
                yieldMl = (spare * WholeChicken.STOCK_ML_PER_GRAM / 50).roundToInt() * 50,
                pressureCooker = KitchenTool.PRESSURE_COOKER in kitchen,
                framesFrozen = lots.count { it.food.name == WholeChicken.FRAME && it.frozen },
            )
        }

        // Freeze: perishable protein (and frames / homemade stock) expiring by tomorrow that no meal will use in time.
        val freeze = lots.filter { !it.frozen && it.expiresOn <= today + 1 && WholeChicken.freezable(it.food) && it.food.name != WholeChicken.FRAME }
            .mapNotNull { lot ->
                val need = (demand[lot.food.name].orEmpty() + MealPlanner.ALIASES.filterValues { lot.food.name in it }.keys.flatMap { demand[it].orEmpty() })
                    .filter { it.first <= lot.expiresOn }.sumOf { it.second }
                val spareG = lot.grams - need
                if (spareG > 20) Move(lot.id, lot.food, spareG, label(lot.food, spareG, kg), "keeps ${WholeChicken.freezerDays(lot.food) / 30} months frozen") else null
            }
        if (freeze.isNotEmpty()) out += Freeze(freeze)
        return out
    }
}
