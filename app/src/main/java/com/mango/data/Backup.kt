package com.mango.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A complete, human-readable save of everything the user created or changed: profile and options,
 * custom / edited / removed recipes, own foods, pantry, scanned barcodes, benchmarks and the meal plan.
 * Bundled catalog data (foods, recipes, exercises) is not stored – it ships with the app.
 *
 * Database ids differ between installs, so everything is cross-referenced by name; recipe ids are kept
 * only to link custom recipes to the meals that use them.
 */
@Serializable
data class MangoBackup(
    val format: Int = BackupCodec.FORMAT,
    val app: String = "Mango",
    val appVersion: String = "",
    val createdAt: String = "",
    val profile: UserProfile? = null,
    /** Foods the user created (manual entry or Open Food Facts). */
    val userFoods: List<Food> = emptyList(),
    /** Custom recipes and bundled recipes the user edited. */
    val recipes: List<Recipe> = emptyList(),
    /** Names of bundled recipes removed from the book. */
    val removedRecipes: List<String> = emptyList(),
    /** Names of every recipe referenced by [meals] or breakfast picks, by original id. */
    val recipeNames: Map<Long, String> = emptyMap(),
    val pantry: List<BackupPantryItem> = emptyList(),
    val barcodes: List<BackupBarcode> = emptyList(),
    val benchmarks: List<Benchmark> = emptyList(),
    val meals: List<MealSlot> = emptyList(),
)

@Serializable
data class BackupPantryItem(val food: String, val grams: Double, val expiresOnEpochDay: Long, val addedOnEpochDay: Long)

@Serializable
data class BackupBarcode(val barcode: String, val productName: String, val food: String)

/** What a backup contains, shown before restoring. */
data class BackupSummary(
    val createdAt: String,
    val customRecipes: Int,
    val editedRecipes: Int,
    val removedRecipes: Int,
    val userFoods: Int,
    val pantryItems: Int,
    val meals: Int,
    val benchmarks: Int,
    val hasProfile: Boolean,
)

object BackupCodec {
    const val FORMAT = 1
    const val MIME = "application/json"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    fun build(
        profile: UserProfile?,
        foods: List<Food>,
        recipes: List<Recipe>,
        pantry: List<PantryItem>,
        barcodes: List<BarcodeProduct>,
        benchmarks: List<Benchmark>,
        meals: List<MealSlot>,
        appVersion: String,
        createdAt: String,
    ): MangoBackup {
        val foodName = foods.associate { it.id to it.name }
        val recipeById = recipes.associateBy { it.id }
        val referenced = meals.map { it.recipeId } + profile?.breakfastPicks.orEmpty()
        return MangoBackup(
            appVersion = appVersion,
            createdAt = createdAt,
            profile = profile,
            userFoods = foods.filter { it.isUserAdded },
            recipes = recipes.filter { it.isCustom || it.isUserModified },
            removedRecipes = recipes.filter { it.isHidden && !it.isCustom }.map { it.name },
            recipeNames = referenced.distinct().mapNotNull { id -> recipeById[id]?.let { id to it.name } }.toMap(),
            pantry = pantry.mapNotNull { p -> foodName[p.foodId]?.let { BackupPantryItem(it, p.grams, p.expiresOnEpochDay, p.addedOnEpochDay) } },
            barcodes = barcodes.mapNotNull { b -> foodName[b.foodId]?.let { BackupBarcode(b.barcode, b.productName, it) } },
            benchmarks = benchmarks,
            meals = meals,
        )
    }

    fun encode(backup: MangoBackup): String = json.encodeToString(MangoBackup.serializer(), backup)

    /** Parses a backup file; throws [IllegalArgumentException] with a readable message when it isn't one. */
    fun decode(text: String): MangoBackup {
        val backup = try {
            json.decodeFromString(MangoBackup.serializer(), text)
        } catch (e: Exception) {
            throw IllegalArgumentException("This file is not a Mango backup.")
        }
        require(backup.app == "Mango") { "This file is not a Mango backup." }
        require(backup.format <= FORMAT) { "This backup comes from a newer Mango – update the app first." }
        return backup
    }

    fun summary(b: MangoBackup) = BackupSummary(
        createdAt = b.createdAt,
        customRecipes = b.recipes.count { it.isCustom },
        editedRecipes = b.recipes.count { !it.isCustom },
        removedRecipes = b.removedRecipes.size + b.recipes.count { it.isCustom && it.isHidden },
        userFoods = b.userFoods.size,
        pantryItems = b.pantry.size,
        meals = b.meals.size,
        benchmarks = b.benchmarks.size,
        hasProfile = b.profile != null,
    )

    /**
     * Maps a recipe id from the backup to an id in this install: custom recipes through [customIds]
     * (old id → newly inserted id), everything else by name.
     */
    fun recipeResolver(b: MangoBackup, customIds: Map<Long, Long>, idByName: Map<String, Long>): (Long) -> Long? = { old ->
        customIds[old] ?: b.recipeNames[old]?.let { idByName[it] }
    }

    /** Meals whose recipe still exists here, re-pointed at local ids. */
    fun restoredMeals(b: MangoBackup, resolve: (Long) -> Long?): List<MealSlot> =
        b.meals.mapNotNull { m -> resolve(m.recipeId)?.let { m.copy(recipeId = it) } }

    fun restoredProfile(b: MangoBackup, resolve: (Long) -> Long?): UserProfile? =
        b.profile?.let { p -> p.copy(breakfastPicks = p.breakfastPicks.mapNotNull(resolve)) }
}
