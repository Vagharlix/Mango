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
            breakfastVariety = p.breakfastVariety.coerceIn(1, 7),
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
            val (updated, emptied) = MealPlanner.consume(pantryDao.getAll(), foodIds, recipe, portions, slot.swaps)
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
        val req = PlanRequest(
            recipes = recipeDao.getAll(),
            foods = foods.associateBy { it.name },
            pantry = stock(foods, p),
            today = today,
            dislikes = p.dislikedIngredients,
            southernHemisphere = p.southernHemisphere,
            produceBoost = boost,
            breakfastVariety = p.breakfastVariety,
            existing = existing,
            exclude = exclude,
            targetsFor = { targetsFor(p, it) },
        )
        mealDao.upsertAll(MealPlanner.plan(toFill, req))
        consolidateLocked(p)
    }

    /** One member per interchangeable group (beans, berries…) across the week's planned meals. */
    private suspend fun consolidateLocked(p: UserProfile) {
        val today = today()
        val foods = foodDao.getAll()
        val changed = MealPlanner.consolidate(
            slots = mealDao.getRange(today, today + 6),
            recipes = recipeDao.getAll().associateBy { it.id },
            foods = foods.associateBy { it.name },
            pantry = stock(foods, p),
            dislikes = p.dislikedIngredients,
        )
        if (changed.isNotEmpty()) mealDao.upsertAll(changed)
    }

    private suspend fun stock(foods: List<Food>, p: UserProfile? = null): List<StockItem> {
        val byId = foods.associateBy { it.id }
        val real = pantryDao.getAll().mapNotNull { item ->
            byId[item.foodId]?.let { StockItem(it.name, item.grams, item.expiresOnEpochDay, it.category == FoodCategory.PRODUCE) }
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
        MealPlanner.shoppingList(
            slots = slots,
            recipes = recipes.associateBy { it.id },
            foods = foods.associateBy { it.name },
            pantry = pantry.mapNotNull { i -> byId[i.foodId]?.let { StockItem(it.name, i.grams, i.expiresOnEpochDay, false) } },
            today = window.first,
            alwaysStocked = keptStaples(foods, p),
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
}
