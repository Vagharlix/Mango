package com.mango.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.mango.domain.DailyTargets
import com.mango.domain.FoodMatcher
import com.mango.domain.FoodPreferences
import com.mango.domain.MealPlanner
import com.mango.domain.NutritionEngine
import com.mango.domain.Periodization
import com.mango.domain.PlanRequest
import com.mango.domain.ShoppingItem
import com.mango.domain.StockItem
import com.mango.domain.KitchenPrep
import com.mango.domain.WholeChicken
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import androidx.room.withTransaction
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import java.io.IOException
import java.time.LocalDate

data class PantryEntry(val item: PantryItem, val food: Food)

sealed interface ScanResult {
    val barcode: String

    /** Barcode already cached locally – works offline. */
    data class Known(override val barcode: String, val productName: String, val food: Food) : ScanResult

    /** Fetched from Open Food Facts; user confirms the matching catalog food. */
    data class Remote(override val barcode: String, val product: OffProduct, val suggestions: List<Food>) : ScanResult

    data class Unknown(override val barcode: String, val reason: String) : ScanResult
}

/** Single source of truth. All planning is delegated to the pure-Kotlin engines in `domain`. */
class Repository(private val context: Context, private val db: MangoDatabase) {
    private val json = Json { ignoreUnknownKeys = true }
    private val planLock = Mutex()
    private val seeded = CompletableDeferred<Unit>()

    private val profileDao = db.profileDao()
    private val foodDao = db.foodDao()
    private val pantryDao = db.pantryDao()
    private val recipeDao = db.recipeDao()
    private val mealDao = db.mealSlotDao()

    val profile: Flow<UserProfile?> = profileDao.observe()
    val foods: Flow<List<Food>> = foodDao.observeAll()
    val recipes: Flow<List<Recipe>> = recipeDao.observeAll()
    val exercises: Flow<List<Exercise>> = db.exerciseDao().observeAll()
    val benchmarks: Flow<List<Benchmark>> = db.benchmarkDao().observeAll()
    val pantry: Flow<List<PantryEntry>> = combine(pantryDao.observeAll(), foods) { items, fs ->
        val byId = fs.associateBy { it.id }
        items.mapNotNull { item -> byId[item.foodId]?.let { PantryEntry(item, it) } }
    }

    fun today(): Long = LocalDate.now().toEpochDay()

    fun slots(from: Long, to: Long): Flow<List<MealSlot>> = mealDao.observeRange(from, to)

    fun targetsFor(p: UserProfile, day: Long): DailyTargets = NutritionEngine.targets(p, Periodization.phaseOn(p, day))

    /** Days covered by the next shopping trip: today until the day before the next grocery day. */
    fun shoppingWindow(p: UserProfile, today: Long = today()): LongRange {
        val dow = LocalDate.ofEpochDay(today).dayOfWeek.value
        val untilNext = (p.groceryDay - dow + 7) % 7
        return today..(if (untilNext == 0) today + 6 else today + untilNext - 1)
    }

    // ---------------- Seeding ----------------

    suspend fun seedIfNeeded() = withContext(Dispatchers.IO) {
        try {
            syncSeedFoods()
            // Exercises are read-only reference data: refresh on every start so asset updates (new protocols,
            // equipment requirements) reach existing installs.
            db.exerciseDao().insertAll(readAsset<List<Exercise>>("exercises.json"))
            syncSeedRecipes()
            seeded.complete(Unit)
        } catch (e: Exception) {
            seeded.completeExceptionally(e)
            throw e
        }
    }

    /** Adds new catalog foods and refreshes bundled ones (nutrients, groups), keeping ids and user-added foods. */
    private suspend fun syncSeedFoods() {
        val existing = foodDao.getAll().associateBy { it.name }
        val seeds = readAsset<List<Food>>("foods.json")
        foodDao.insertAll(seeds.filter { it.name !in existing })
        val changed = seeds.mapNotNull { seed ->
            val old = existing[seed.name]?.takeUnless { it.isUserAdded } ?: return@mapNotNull null
            seed.copy(id = old.id).takeIf { it != old }
        }
        if (changed.isNotEmpty()) foodDao.updateAll(changed)
    }

    /**
     * Inserts new bundled recipes and refreshes bundled ones the user never edited, so recipe corrections
     * in app updates reach existing installs. Custom and user-modified recipes are never touched.
     */
    private suspend fun syncSeedRecipes() {
        val byName = foodDao.getAll().associateBy { it.name }
        val existing = recipeDao.getAll().associateBy { it.name }
        val seeds = readAsset<List<Recipe>>("recipes.json").map { NutritionEngine.withNutrients(it, byName) }
        val fresh = seeds.filter { it.name !in existing }
        if (fresh.isNotEmpty()) recipeDao.insertAll(fresh)
        val changed = seeds.mapNotNull { seed ->
            val old = existing[seed.name]?.takeUnless { it.isCustom || it.isUserModified } ?: return@mapNotNull null
            seed.copy(id = old.id, isHidden = old.isHidden).takeIf { it != old }
        }
        changed.forEach { recipeDao.update(it) }
        if (changed.isNotEmpty()) planLock.withLock { reportionLocked(changed.associateBy { it.id }) }
    }

    private inline fun <reified T> readAsset(name: String): T =
        context.assets.open(name).bufferedReader().use { json.decodeFromString<T>(it.readText()) }

    // ---------------- Profile & training ----------------

    suspend fun saveProfile(p: UserProfile) {
        val clean = p.copy(
            programStartEpochDay = if (p.programStartEpochDay == 0L) today() else p.programStartEpochDay,
            dislikedIngredients = p.dislikedIngredients.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct(),
            programWeeks = p.programWeeks.coerceIn(4, 52),
            breakfastVariety = p.breakfastVariety.coerceIn(2, 7),
        )
        val previous = profileDao.get()
        profileDao.upsert(clean)
        planLock.withLock { dropDisallowedLocked(clean) }
        if (previous != null && previous.breakfastVariety != clean.breakfastVariety) regenerateWeek() else ensureWeekPlan()
    }

    /** Equipment can change at any time (gym, home, travel); the schedule is derived from it on the fly. */
    suspend fun updateEquipment(equipment: Collection<Equipment>) {
        val p = profileDao.get() ?: throw IllegalStateException("Create your profile first")
        profileDao.upsert(p.copy(equipment = Equipment.entries.filter { it in equipment }))
    }

    suspend fun addBenchmark(fingerAddedKg: Double, pullUpAddedKg: Double) {
        val p = profileDao.get() ?: throw IllegalStateException("Create your profile first")
        db.benchmarkDao().insert(Benchmark(epochDay = today(), fingerMaxAddedKg = fingerAddedKg, pullUpMaxAddedKg = pullUpAddedKg, bodyWeightKg = p.weightKg))
    }

    // ---------------- Meal planning ----------------

    /** Fills any empty slot in the coming 7 days. */
    suspend fun ensureWeekPlan() = planLock.withLock {
        val p = profileDao.get() ?: return@withLock
        val today = today()
        val have = mealDao.getRange(today, today + 6).map { it.epochDay to it.mealType }.toSet()
        val missing = weekKeys(today).filter { it !in have }
        if (missing.isNotEmpty()) planLocked(p, missing)
    }

    /** Re-plans every not-yet-cooked meal of the coming week. */
    suspend fun regenerateWeek() = planLock.withLock {
        val p = profileDao.get() ?: return@withLock
        val today = today()
        val done = mealDao.getRange(today, today + 6).filter { it.status != MealStatus.PLANNED }.map { it.epochDay to it.mealType }.toSet()
        planLocked(p, weekKeys(today).filter { it !in done })
    }

    /** "Swap": replace one meal, never offering the current or previously rejected recipes. */
    /** Swapping a batch's cooking meal replaces the whole batch; swapping a leftover frees just that meal. */
    suspend fun swap(slot: MealSlot, rejected: Set<Long>) = planLock.withLock {
        val p = profileDao.get() ?: return@withLock
        val keys = (listOf(slot) + if (slot.batchId != 0L && !slot.isLeftover) batchOf(slot).filter { it.status == MealStatus.PLANNED } else emptyList())
            .map { it.epochDay to it.mealType }.distinct()
        planLocked(p, keys, exclude = rejected + slot.recipeId)
    }

    private suspend fun batchOf(slot: MealSlot): List<MealSlot> =
        if (slot.batchId == 0L) {
            listOf(slot)
        } else {
            mealDao.getRange(slot.epochDay - MealPlanner.BATCH_WINDOW_DAYS, slot.epochDay + MealPlanner.BATCH_WINDOW_DAYS)
                .filter { it.batchId == slot.batchId }
        }

    /** "Don't like": hide the recipe permanently and re-plan every future slot that used it. */
    suspend fun hideRecipe(recipeId: Long) = planLock.withLock {
        val p = profileDao.get() ?: return@withLock
        recipeDao.setHidden(recipeId, true)
        val affected = mealDao.getRange(today(), today() + 13)
            .filter { it.status == MealStatus.PLANNED && it.recipeId == recipeId }
        mealDao.deletePlannedForRecipe(recipeId, today())
        planLocked(p, affected.map { it.epochDay to it.mealType })
    }

    suspend fun setRecipeHidden(recipeId: Long, hidden: Boolean) = recipeDao.setHidden(recipeId, hidden)

    /** Adds an ingredient to the dislike list and replaces every planned meal containing it. */
    suspend fun addDislike(term: String) {
        val p = profileDao.get() ?: return
        val t = term.trim().lowercase()
        if (t.isEmpty() || t in p.dislikedIngredients) return
        val updated = p.copy(dislikedIngredients = p.dislikedIngredients + t)
        profileDao.upsert(updated)
        planLock.withLock { dropDisallowedLocked(updated) }
        ensureWeekPlan()
    }

    private suspend fun dropDisallowedLocked(p: UserProfile) {
        val byName = foodDao.getAll().associateBy { it.name }
        val recipes = recipeDao.getAll().associateBy { it.id }
        val bad = mealDao.getRange(today(), today() + 13).filter { s ->
            s.status == MealStatus.PLANNED &&
                recipes[s.recipeId]?.let { MealPlanner.isAllowed(it, p.dislikedIngredients, byName) } != true
        }
        if (bad.isNotEmpty()) mealDao.deleteAll(bad)
    }

    /** Cooked: deduct ingredients from the pantry (first-expiry-first-out). */
    /**
     * Cooked: deduct ingredients from the pantry (first-expiry-first-out). Cooking a batch deducts every
     * planned meal of the batch at once; eating its leftovers later deducts nothing more.
     */
    suspend fun cook(slot: MealSlot) = planLock.withLock {
        val recipe = recipeDao.getById(slot.recipeId) ?: throw IllegalStateException("Recipe no longer exists")
        val batch = batchOf(slot)
        val portions = when {
            slot.isLeftover -> if (MealPlanner.needsIngredients(slot, batch)) slot.portions else 0.0
            else -> slot.portions + batch.filter { it.isLeftover && it.status == MealStatus.PLANNED }.sumOf { it.portions }
        }
        if (portions > 0) {
            val foodIds = foodDao.getAll().associate { it.name to it.id }
            val (updated, emptied) = MealPlanner.consume(pantryDao.getAll(), foodIds, recipe, portions, slot.swaps, foodDao.getAll().associateBy { it.name })
            updated.forEach { pantryDao.update(it) }
            emptied.forEach { pantryDao.delete(it) }
        }
        mealDao.upsertAll(listOf(slot.copy(status = MealStatus.COOKED)))
    }

    /**
     * Zero-food-waste skip / dining out: no macro compensation, no pantry deduction, and the next
     * three days are re-planned with the expiring-produce boost. Unused food stays in the pantry,
     * which automatically shrinks the next shopping list.
     */
    suspend fun skip(slot: MealSlot) = planLock.withLock {
        val p = profileDao.get() ?: return@withLock
        mealDao.upsertAll(listOf(slot.copy(status = MealStatus.SKIPPED)))
        val until = maxOf(today(), slot.epochDay) + 2
        val affected = mealDao.getRange(slot.epochDay, until).filter {
            it.status == MealStatus.PLANNED &&
                (it.epochDay > slot.epochDay || it.mealType.ordinal > slot.mealType.ordinal)
        }
        // Skipping a batch's cooking meal means its leftovers won't exist either: re-plan them too.
        val orphans = if (!slot.isLeftover) batchOf(slot).filter { it.isLeftover && it.status == MealStatus.PLANNED } else emptyList()
        planLocked(p, (affected + orphans).map { it.epochDay to it.mealType }.distinct(), boost = true)
    }

    suspend fun unskip(slot: MealSlot) = mealDao.upsertAll(listOf(slot.copy(status = MealStatus.PLANNED)))

    private fun weekKeys(today: Long) = (today..today + 6).flatMap { d -> MealType.entries.map { d to it } }

    private suspend fun planLocked(
        p: UserProfile,
        toFill: List<Pair<Long, MealType>>,
        boost: Boolean = false,
        exclude: Set<Long> = emptySet(),
    ) {
        if (toFill.isEmpty()) return
        seeded.await()
        val today = today()
        val foods = foodDao.getAll()
        val keys = toFill.toSet()
        val existing = mealDao.getRange(today - MealPlanner.ANTI_REPEAT_DAYS, today + 14)
            .filterNot { (it.epochDay to it.mealType) in keys }
        val byName = foods.associateBy { it.name }
        val base = stock(foods, p)
        val recipes = adaptedRecipes(p, byName)
        val req = PlanRequest(
            recipes = recipes,
            foods = byName,
            pantry = base,
            today = today,
            dislikes = p.dislikedIngredients,
            southernHemisphere = p.southernHemisphere,
            produceBoost = boost,
            breakfastVariety = p.breakfastVariety,
            breakfastPicks = p.breakfastPicks.toSet(),
            existing = existing,
            exclude = exclude,
            kitchen = p.kitchen.toSet(),
            targetsFor = { targetsFor(p, it) },
        )
        mealDao.upsertAll(planWithBird(p, toFill, req, existing, recipes, byName))
        consolidateLocked(p)
    }

    /**
     * Whole chicken: plan the week around a bird that isn't bought yet (its parts count as stock, used fresh
     * or frozen). "When it fits" keeps that plan only if it uses at least half the bird's meat.
     */
    private suspend fun planWithBird(
        p: UserProfile,
        toFill: List<Pair<Long, MealType>>,
        req: PlanRequest,
        existing: List<MealSlot>,
        recipes: List<Recipe>,
        foods: Map<String, Food>,
    ): List<MealSlot> {
        val today = req.today
        val lots = pantryDao.getAll()
        val byId = foods.values.associateBy { it.id }
        val partsInStock = lots.mapNotNull { l -> byId[l.foodId]?.name?.takeIf { it in WholeChicken.PART_FOODS }?.let { it to l.grams } }
            .groupBy({ it.first }, { it.second }).mapValues { it.value.sum() }
        val boughtThisWeek = lots.any { l -> byId[l.foodId]?.name in WholeChicken.PART_FOODS && l.addedOnEpochDay >= lastGroceryDay(p) }
        val noChicken = "chicken" in p.proteinOff || p.dislikedIngredients.any { MealPlanner.matchesDislike(foods[WholeChicken.BREAST], WholeChicken.BREAST, it) }
        if (noChicken || !WholeChicken.wantsVirtualBird(p.wholeChicken, boughtThisWeek, partsInStock, p.chickenKg)) return MealPlanner.plan(toFill, req)
        val withBird = MealPlanner.plan(toFill, req.copy(pantry = req.pantry + WholeChicken.virtualStock(p.chickenKg, today, today + 6), scarce = WholeChicken.PART_FOODS))
        if (p.wholeChicken == WholeChickenMode.ALWAYS) return withBird
        val week = (existing + withBird).filter { it.epochDay in today..today + 6 }
        val used = WholeChicken.meatUsed(week, recipes.associateBy { it.id }, foods, today..today + 6)
        return if (WholeChicken.fits(used, p.chickenKg)) withBird else MealPlanner.plan(toFill, req)
    }

    /** The most recent grocery day (today if it is one). */
    private fun lastGroceryDay(p: UserProfile, today: Long = today()): Long {
        val dow = LocalDate.ofEpochDay(today).dayOfWeek.value
        return today - (dow - p.groceryDay + 7) % 7
    }

    /** One member per interchangeable group (beans, berries…) across the week's planned meals. */
    private suspend fun consolidateLocked(p: UserProfile) {
        val today = today()
        val foods = foodDao.getAll()
        val byName = foods.associateBy { it.name }
        val changed = MealPlanner.consolidate(
            slots = mealDao.getRange(today, today + 6),
            recipes = recipeDao.getAll().associateBy { it.id },
            foods = byName,
            pantry = stock(foods, p),
            dislikes = p.dislikedIngredients,
            baseSwaps = { preferenceSwaps(it, p, byName).orEmpty() },
        )
        if (changed.isNotEmpty()) mealDao.upsertAll(changed)
    }

    private fun activeManualSwaps(p: UserProfile): Map<String, String> =
        if (today() <= p.manualSwapsUntil) p.manualSwaps else emptyMap()

    private fun preferenceSwaps(r: Recipe, p: UserProfile, foods: Map<String, Food>): Map<String, String>? =
        FoodPreferences.swaps(r, p.carbOff, p.proteinOff, activeManualSwaps(p), p.dislikedIngredients, foods)

    /**
     * Recipes as the planner should see them: adapted to the user's carb/protein choices and replacements
     * (macros recomputed), recipes that can't be adapted left out.
     */
    private suspend fun adaptedRecipes(p: UserProfile, foods: Map<String, Food>): List<Recipe> =
        recipeDao.getAll().mapNotNull { r ->
            val swaps = preferenceSwaps(r, p, foods) ?: return@mapNotNull null
            if (swaps.isEmpty()) r else NutritionEngine.withNutrients(r.copy(ingredients = MealPlanner.effectiveIngredients(r, swaps, foods)), foods)
        }

    /** Carb / protein types and breakfast picks; the not-yet-cooked meals of the week are re-planned. */
    suspend fun updateFoodPreferences(carbOff: List<String>, proteinOff: List<String>, breakfastPicks: List<Long>, breakfastVariety: Int) {
        val p = profileDao.get() ?: throw IllegalStateException("Create your profile first")
        profileDao.upsert(p.copy(carbOff = carbOff, proteinOff = proteinOff, breakfastPicks = breakfastPicks, breakfastVariety = breakfastVariety.coerceIn(2, 7)))
        regenerateWeek()
    }

    /** "Can't find it": use [replacement] instead of [food] in this week's meals, until the next grocery day. */
    suspend fun replaceIngredient(food: String, replacement: String) = planLock.withLock {
        val p = profileDao.get() ?: return@withLock
        val updated = p.copy(manualSwaps = activeManualSwaps(p) + (food to replacement), manualSwapsUntil = shoppingWindow(p).last)
        profileDao.upsert(updated)
        consolidateLocked(updated)
    }

    suspend fun clearReplacement(food: String) = planLock.withLock {
        val p = profileDao.get() ?: return@withLock
        val updated = p.copy(manualSwaps = p.manualSwaps - food)
        profileDao.upsert(updated)
        consolidateLocked(updated)
    }

    private suspend fun stock(foods: List<Food>, p: UserProfile? = null): List<StockItem> {
        val byId = foods.associateBy { it.id }
        val real = pantryDao.getAll().mapNotNull { item ->
            byId[item.foodId]?.let {
                // Frozen food needs a night in the fridge before it can be cooked.
                StockItem(
                    it.name, item.grams, item.expiresOnEpochDay, it.category == FoodCategory.PRODUCE,
                    availableFrom = if (item.frozen) today() + 1 else Long.MIN_VALUE,
                    // Pieces of a bird and homemade stock waiting in the freezer get used up first.
                    priority = item.frozen && (it.name in WholeChicken.PART_FOODS || it.name == WholeChicken.STOCK),
                )
            }
        }
        // The spice rack is always considered stocked, so staples never count as missing.
        val rack = if (p == null) emptyList() else keptStaples(foods, p).map { StockItem(it, 100_000.0, today() + 3650, false) }
        return real + rack
    }

    fun keptStaples(foods: List<Food>, p: UserProfile): Set<String> =
        foods.filter { it.isStaple && it.name !in p.stapleOptOut }.map { it.name }.toSet()

    /** Spice rack: which staples the user keeps stocked themselves (default: all). */
    suspend fun setStapleKept(name: String, kept: Boolean) {
        val p = profileDao.get() ?: throw IllegalStateException("Create your profile first")
        val optOut = if (kept) p.stapleOptOut - name else (p.stapleOptOut + name).distinct()
        profileDao.upsert(p.copy(stapleOptOut = optOut))
    }

    fun shoppingList(window: LongRange, p: UserProfile): Flow<List<ShoppingItem>> = combine(
        // A couple of days earlier too, so leftovers know whether their batch has been cooked.
        slots(window.first - MealPlanner.BATCH_WINDOW_DAYS, window.last), recipes, foods, pantryDao.observeAll(),
    ) { slots, recipes, foods, pantry ->
        val byId = foods.associateBy { it.id }
        val byName = foods.associateBy { it.name }
        val items = MealPlanner.shoppingList(
            slots = slots,
            recipes = recipes.associateBy { it.id },
            foods = byName,
            pantry = pantry.mapNotNull { i -> byId[i.foodId]?.let { StockItem(it.name, i.grams, i.expiresOnEpochDay, false) } },
            today = window.first,
            alwaysStocked = keptStaples(foods, p),
        )
        WholeChicken.toBirds(items, p.wholeChicken, p.chickenKg, byName[WholeChicken.WHOLE])
    }

    // ---------------- Pantry ----------------

    suspend fun addToPantry(foodId: Long, grams: Double, expiresOn: Long) {
        require(grams > 0) { "Quantity must be greater than zero" }
        pantryDao.insert(PantryItem(foodId = foodId, grams = grams, expiresOnEpochDay = expiresOn, addedOnEpochDay = today()))
    }

    suspend fun markBought(item: ShoppingItem) =
        if (item.food.name == WholeChicken.WHOLE) {
            buyBirds(maxOf(1, Math.round(item.grams / (item.food.unitGrams ?: 1600.0)).toInt()))
        } else {
            addToPantry(item.food.id, item.grams, today() + item.food.shelfLifeDays)
        }

    /**
     * Whole birds bought: their pieces go straight into the pantry – those the plan eats today or tomorrow in
     * the fridge, the rest in the freezer – and a breakdown task appears.
     */
    private suspend fun buyBirds(birds: Int) = planLock.withLock {
        val p = profileDao.get() ?: throw IllegalStateException("Create your profile first")
        val today = today()
        val foods = foodDao.getAll()
        val byName = foods.associateBy { it.name }
        val byId = foods.associateBy { it.id }
        val slots = mealDao.getRange(today - MealPlanner.BATCH_WINDOW_DAYS, today + 13)
        val demand = WholeChicken.demand(slots, recipeDao.getAll().associateBy { it.id }, byName, today..today + 13).toMutableMap()
        // Fresh pieces already in the fridge cover the earliest needs.
        pantryDao.getAll().filter { !it.frozen }.forEach { lot ->
            val name = byId[lot.foodId]?.name ?: return@forEach
            var left = lot.grams
            demand[name] = demand[name].orEmpty().map { (d, g) -> val t = minOf(g, left); left -= t; d to g - t }.filter { it.second > 0.5 }
        }
        WholeChicken.allocate(birds, p.chickenKg, today, demand).forEach { lot ->
            val food = byName[lot.food] ?: return@forEach
            val expires = if (lot.frozen) today + WholeChicken.freezerDays(food) else today + food.shelfLifeDays
            pantryDao.insert(PantryItem(foodId = food.id, grams = lot.grams, expiresOnEpochDay = expires, addedOnEpochDay = today, frozen = lot.frozen))
        }
        profileDao.upsert(p.copy(breakdownOn = today))
    }

    suspend fun breakdownDone() {
        val p = profileDao.get() ?: return
        profileDao.upsert(p.copy(breakdownOn = 0))
    }

    /** Moves [grams] of a lot into the freezer (the rest stays in the fridge). */
    suspend fun freeze(lotId: Long, grams: Double = Double.MAX_VALUE) = moveLot(lotId, grams, toFreezer = true)

    /** Moves [grams] of a frozen lot to the fridge to thaw. */
    suspend fun thaw(lotId: Long, grams: Double = Double.MAX_VALUE) = moveLot(lotId, grams, toFreezer = false)

    private suspend fun moveLot(lotId: Long, grams: Double, toFreezer: Boolean) {
        val lot = pantryDao.getAll().firstOrNull { it.id == lotId } ?: return
        val food = foodDao.getById(lot.foodId) ?: return
        val today = today()
        val moved = minOf(grams, lot.grams)
        // Frozen: months for quality. Thawed in the fridge: raw meat 1-2 days, stock 3-4 days (FoodSafety.gov).
        val expires = if (toFreezer) today + WholeChicken.freezerDays(food) else today + food.shelfLifeDays
        if (moved >= lot.grams - 0.5) {
            pantryDao.update(lot.copy(frozen = toFreezer, expiresOnEpochDay = expires))
        } else {
            pantryDao.update(lot.copy(grams = lot.grams - moved))
            pantryDao.insert(PantryItem(foodId = lot.foodId, grams = moved, expiresOnEpochDay = expires, addedOnEpochDay = lot.addedOnEpochDay, frozen = toFreezer))
        }
    }

    /**
     * Stock made from the frames: they leave the pantry and homemade stock arrives – what the plan uses in the
     * next 3 days in the fridge, the rest frozen in boxes.
     */
    suspend fun makeStock(lotIds: List<Long>, frameGrams: Double, ml: Double) = planLock.withLock {
        val today = today()
        val foods = foodDao.getAll()
        val byName = foods.associateBy { it.name }
        val stockFood = byName[WholeChicken.STOCK] ?: return@withLock
        var left = frameGrams
        pantryDao.getAll().filter { it.id in lotIds }.forEach { lot ->
            val t = minOf(lot.grams, left)
            left -= t
            if (lot.grams - t <= 0.5) pantryDao.delete(lot.id) else pantryDao.update(lot.copy(grams = lot.grams - t))
        }
        val slots = mealDao.getRange(today - MealPlanner.BATCH_WINDOW_DAYS, today + 13)
        val demand = WholeChicken.demand(slots, recipeDao.getAll().associateBy { it.id }, byName, today..today + 3)
        val soon = MealPlanner.ALIASES.filterValues { WholeChicken.STOCK in it }.keys.plus(WholeChicken.STOCK)
            .sumOf { n -> demand[n].orEmpty().sumOf { it.second } }
        val fresh = minOf(ml, soon)
        if (fresh > 1) pantryDao.insert(PantryItem(foodId = stockFood.id, grams = fresh, expiresOnEpochDay = today + stockFood.shelfLifeDays, addedOnEpochDay = today))
        if (ml - fresh > 1) pantryDao.insert(PantryItem(foodId = stockFood.id, grams = ml - fresh, expiresOnEpochDay = today + WholeChicken.freezerDays(stockFood), addedOnEpochDay = today, frozen = true))
    }

    /** Today's kitchen jobs: break down a new bird, make stock, thaw, freeze. */
    fun prepTasks(p: UserProfile): Flow<List<KitchenPrep.Task>> = combine(
        slots(today() - MealPlanner.BATCH_WINDOW_DAYS, today() + 6), recipes, foods, pantryDao.observeAll(),
    ) { slots, recipes, foods, pantry ->
        val today = today()
        val byId = foods.associateBy { it.id }
        val lots = pantry.mapNotNull { i -> byId[i.foodId]?.let { KitchenPrep.PantryLot(i.id, it, i.grams, i.expiresOnEpochDay, i.frozen, i.addedOnEpochDay) } }
        KitchenPrep.tasks(
            today = today,
            lots = lots,
            demand = WholeChicken.demand(slots, recipes.associateBy { it.id }, foods.associateBy { it.name }, today..today + 6),
            breakdownOn = p.breakdownOn,
            kg = p.chickenKg,
            kitchen = p.kitchen,
            mealLabel = { d -> if (d == today + 1) "tomorrow" else LocalDate.ofEpochDay(d).dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault()) },
        )
    }

    /** Whole chicken mode, bird size and appliances; the week is re-planned around them. */
    suspend fun updateKitchen(mode: WholeChickenMode, chickenKg: Double, kitchen: Collection<KitchenTool>) {
        val p = profileDao.get() ?: throw IllegalStateException("Create your profile first")
        profileDao.upsert(p.copy(wholeChicken = mode, chickenKg = chickenKg.coerceIn(0.8, 3.0), kitchen = KitchenTool.entries.filter { it in kitchen }))
        regenerateWeek()
    }

    suspend fun updatePantry(item: PantryItem) =
        if (item.grams <= 0.5) pantryDao.delete(item.id) else pantryDao.update(item)

    suspend fun deletePantry(id: Long) = pantryDao.delete(id)

    // ---------------- Barcodes ----------------

    suspend fun lookupBarcode(barcode: String): ScanResult = withContext(Dispatchers.IO) {
        db.barcodeDao().get(barcode)?.let { cached ->
            foodDao.getById(cached.foodId)?.let { return@withContext ScanResult.Known(barcode, cached.productName, it) }
        }
        if (!isOnline()) return@withContext ScanResult.Unknown(barcode, "Offline and not in the local database – pick the matching food below.")
        try {
            val product = OpenFoodFacts.fetch(barcode)
                ?: return@withContext ScanResult.Unknown(barcode, "Not found on Open Food Facts – pick the matching food below.")
            ScanResult.Remote(barcode, product, FoodMatcher.suggest("${product.name} ${product.categories}", foodDao.getAll()))
        } catch (e: IOException) {
            ScanResult.Unknown(barcode, "Network error (${e.message}) – pick the matching food below.")
        }
    }

    /** Caches the barcode → food mapping so future scans resolve instantly offline. */
    suspend fun linkBarcode(barcode: String, productName: String, foodId: Long) =
        db.barcodeDao().upsert(BarcodeProduct(barcode, productName, foodId))

    /** Creates a new catalog food from Open Food Facts nutrition (usable in custom recipes) and links the barcode. */
    suspend fun createFoodFromProduct(product: OffProduct): Food {
        var name = product.name.lowercase().take(60)
        if (foodDao.countByName(name) > 0) name = "$name (${product.barcode.takeLast(4)})"
        val n = product.nutrients
        val food = Food(
            name = name, category = FoodCategory.OTHER, kcal = n.kcal, protein = n.protein, carbs = n.carbs,
            fat = n.fat, fiber = n.fiber, vitaminC = n.vitaminC, omega3 = n.omega3, shelfLifeDays = 14, isUserAdded = true,
        )
        val saved = food.copy(id = foodDao.insert(food))
        linkBarcode(product.barcode, product.name, saved.id)
        return saved
    }

    private fun isOnline(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    // ---------------- Recipes ----------------

    suspend fun getRecipe(id: Long): Recipe? = recipeDao.getById(id)

    /** Recomputes macros on-device; new recipes are flagged custom, edits are flagged user-modified. */
    suspend fun saveRecipe(recipe: Recipe): Long {
        require(recipe.name.isNotBlank()) { "Recipe needs a name" }
        require(recipe.ingredients.isNotEmpty()) { "Add at least one ingredient" }
        val byName = foodDao.getAll().associateBy { it.name }
        val computed = NutritionEngine.withNutrients(recipe, byName)
        if (recipe.id == 0L) return recipeDao.insert(computed.copy(isCustom = true))
        recipeDao.update(computed.copy(isUserModified = true))
        planLock.withLock { reportionLocked(mapOf(computed.id to computed)) }
        return recipe.id
    }

    /** Keeps portions of already-planned meals on target after a recipe's macros changed. */
    private suspend fun reportionLocked(changed: Map<Long, Recipe>) {
        val p = profileDao.get() ?: return
        val replanned = mealDao.getRange(today(), today() + 13)
            .filter { it.status == MealStatus.PLANNED && it.recipeId in changed }
            .map { it.copy(portions = MealPlanner.portionsFor(changed.getValue(it.recipeId), it.mealType, targetsFor(p, it.epochDay))) }
        if (replanned.isNotEmpty()) mealDao.upsertAll(replanned)
    }

    suspend fun deleteCustomRecipe(id: Long) = planLock.withLock {
        mealDao.deletePlannedForRecipe(id, today())
        recipeDao.deleteCustom(id)
    }

    // ---------------- Backup & restore ----------------

    /** Every table that a backup captures; emits whenever any of them changes (drives the auto-save file). */
    val backupChanges: Flow<Unit> = combine(
        profile, foods, recipes, pantryDao.observeAll(), benchmarks, mealDao.observeRange(Long.MIN_VALUE, Long.MAX_VALUE),
    ) { _ -> Unit }

    suspend fun createBackup(): MangoBackup = withContext(Dispatchers.IO) {
        seeded.await()
        BackupCodec.build(
            profile = profileDao.get(),
            foods = foodDao.getAll(),
            recipes = recipeDao.getAll(),
            pantry = pantryDao.getAll(),
            barcodes = db.barcodeDao().getAll(),
            benchmarks = db.benchmarkDao().getAll(),
            meals = mealDao.getAll(),
            appVersion = appVersion(),
            createdAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
        )
    }

    private fun appVersion(): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
    } catch (e: Exception) {
        ""
    }

    /**
     * Replaces the user's data with a backup. Bundled catalog data stays; the backup's own foods, custom and
     * edited recipes, removed recipes, pantry, barcodes, benchmarks, meal plan and profile are restored.
     */
    suspend fun restore(b: MangoBackup) = withContext(Dispatchers.IO) {
        seeded.await()
        planLock.withLock {
            db.withTransaction {
                // Own foods first: recipes and pantry refer to them by name.
                val existingFoods = foodDao.getAll().associateBy { it.name }
                b.userFoods.forEach { f ->
                    val old = existingFoods[f.name]
                    when {
                        old == null -> foodDao.insert(f.copy(id = 0))
                        old.isUserAdded -> foodDao.updateAll(listOf(f.copy(id = old.id)))
                    }
                }
                val foods = foodDao.getAll()
                val foodByName = foods.associateBy { it.name }

                // Recipes: drop current custom ones, reset bundled ones to "unremoved", then apply the backup.
                mealDao.clear()
                recipeDao.deleteAllCustom()
                val bundled = recipeDao.getAll()
                bundled.filter { it.isHidden }.forEach { recipeDao.setHidden(it.id, false) }
                val bundledByName = bundled.associateBy { it.name }
                val customIds = mutableMapOf<Long, Long>()
                b.recipes.forEach { r ->
                    val computed = NutritionEngine.withNutrients(r, foodByName)
                    val target = bundledByName[r.name]
                    if (!r.isCustom && target != null) {
                        recipeDao.update(computed.copy(id = target.id, isUserModified = true))
                    } else {
                        customIds[r.id] = recipeDao.insert(computed.copy(id = 0, isCustom = true))
                    }
                }
                b.removedRecipes.forEach { name -> bundledByName[name]?.let { recipeDao.setHidden(it.id, true) } }
                val resolve = BackupCodec.recipeResolver(b, customIds, recipeDao.getAll().associate { it.name to it.id })

                pantryDao.clear()
                pantryDao.insertAll(b.pantry.mapNotNull { p ->
                    foodByName[p.food]?.let { PantryItem(foodId = it.id, grams = p.grams, expiresOnEpochDay = p.expiresOnEpochDay, addedOnEpochDay = p.addedOnEpochDay, frozen = p.frozen) }
                })
                b.barcodes.forEach { bc -> foodByName[bc.food]?.let { db.barcodeDao().upsert(BarcodeProduct(bc.barcode, bc.productName, it.id)) } }
                db.benchmarkDao().clear()
                db.benchmarkDao().insertAll(b.benchmarks.map { it.copy(id = 0) })
                mealDao.upsertAll(BackupCodec.restoredMeals(b, resolve))
                BackupCodec.restoredProfile(b, resolve)?.let { profileDao.upsert(it) }
            }
        }
        // Fill any day the backup didn't cover (e.g. an old backup) with a fresh plan.
        ensureWeekPlan()
    }
}
