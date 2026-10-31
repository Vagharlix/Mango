package com.mango.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.mango.domain.DailyTargets
import com.mango.domain.FoodMatcher
import com.mango.domain.MealPlanner
import com.mango.domain.NutritionEngine
import com.mango.domain.Periodization
import com.mango.domain.PlanRequest
import com.mango.domain.ShoppingItem
import com.mango.domain.StockItem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
            if (foodDao.count() == 0) foodDao.insertAll(readAsset<List<Food>>("foods.json"))
            if (db.exerciseDao().count() == 0) db.exerciseDao().insertAll(readAsset<List<Exercise>>("exercises.json"))
            if (recipeDao.count() == 0) {
                val byName = foodDao.getAll().associateBy { it.name }
                recipeDao.insertAll(readAsset<List<Recipe>>("recipes.json").map { NutritionEngine.withNutrients(it, byName) })
            }
            seeded.complete(Unit)
        } catch (e: Exception) {
            seeded.completeExceptionally(e)
            throw e
        }
    }

    private inline fun <reified T> readAsset(name: String): T =
        context.assets.open(name).bufferedReader().use { json.decodeFromString<T>(it.readText()) }

    // ---------------- Profile & training ----------------

    suspend fun saveProfile(p: UserProfile) {
        val clean = p.copy(
            programStartEpochDay = if (p.programStartEpochDay == 0L) today() else p.programStartEpochDay,
            dislikedIngredients = p.dislikedIngredients.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct(),
            programWeeks = p.programWeeks.coerceIn(4, 52),
        )
        profileDao.upsert(clean)
        planLock.withLock { dropDisallowedLocked(clean) }
        ensureWeekPlan()
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
    suspend fun swap(slot: MealSlot, rejected: Set<Long>) = planLock.withLock {
        val p = profileDao.get() ?: return@withLock
        planLocked(p, listOf(slot.epochDay to slot.mealType), exclude = rejected + slot.recipeId)
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
    suspend fun cook(slot: MealSlot) = planLock.withLock {
        val recipe = recipeDao.getById(slot.recipeId) ?: throw IllegalStateException("Recipe no longer exists")
        val foodIds = foodDao.getAll().associate { it.name to it.id }
        val (updated, emptied) = MealPlanner.consume(pantryDao.getAll(), foodIds, recipe, slot.portions)
        updated.forEach { pantryDao.update(it) }
        emptied.forEach { pantryDao.delete(it) }
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
        planLocked(p, affected.map { it.epochDay to it.mealType }, boost = true)
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
        val req = PlanRequest(
            recipes = recipeDao.getAll(),
            foods = foods.associateBy { it.name },
            pantry = stock(foods),
            today = today,
            dislikes = p.dislikedIngredients,
            southernHemisphere = p.southernHemisphere,
            produceBoost = boost,
            existing = existing,
            exclude = exclude,
            targetsFor = { targetsFor(p, it) },
        )
        mealDao.upsertAll(MealPlanner.plan(toFill, req))
    }

    private suspend fun stock(foods: List<Food>): List<StockItem> {
        val byId = foods.associateBy { it.id }
        return pantryDao.getAll().mapNotNull { item ->
            byId[item.foodId]?.let { StockItem(it.name, item.grams, item.expiresOnEpochDay, it.category == FoodCategory.PRODUCE) }
        }
    }

    fun shoppingList(window: LongRange): Flow<List<ShoppingItem>> = combine(
        slots(window.first, window.last), recipes, foods, pantryDao.observeAll(),
    ) { slots, recipes, foods, pantry ->
        val byId = foods.associateBy { it.id }
        MealPlanner.shoppingList(
            slots = slots,
            recipes = recipes.associateBy { it.id },
            foods = foods.associateBy { it.name },
            pantry = pantry.mapNotNull { i -> byId[i.foodId]?.let { StockItem(it.name, i.grams, i.expiresOnEpochDay, false) } },
            today = window.first,
        )
    }

    // ---------------- Pantry ----------------

    suspend fun addToPantry(foodId: Long, grams: Double, expiresOn: Long) {
        require(grams > 0) { "Quantity must be greater than zero" }
        pantryDao.insert(PantryItem(foodId = foodId, grams = grams, expiresOnEpochDay = expiresOn, addedOnEpochDay = today()))
    }

    suspend fun markBought(item: ShoppingItem) =
        addToPantry(item.food.id, item.grams, today() + item.food.shelfLifeDays)

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
        // Keep portions of already-planned meals on target with the new macros.
        planLock.withLock {
            val p = profileDao.get() ?: return@withLock
            val replanned = mealDao.getRange(today(), today() + 13)
                .filter { it.status == MealStatus.PLANNED && it.recipeId == recipe.id }
                .map { it.copy(portions = MealPlanner.portionsFor(computed, it.mealType, targetsFor(p, it.epochDay))) }
            if (replanned.isNotEmpty()) mealDao.upsertAll(replanned)
        }
        return recipe.id
    }

    suspend fun deleteCustomRecipe(id: Long) = planLock.withLock {
        mealDao.deletePlannedForRecipe(id, today())
        recipeDao.deleteCustom(id)
    }
}
