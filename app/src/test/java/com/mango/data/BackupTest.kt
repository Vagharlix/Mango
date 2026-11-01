package com.mango.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {
    private val rice = Food(id = 1, name = "rice", category = FoodCategory.GRAIN, kcal = 360.0, protein = 7.0, carbs = 79.0, fat = 1.0)
    private val myBar = Food(id = 2, name = "my protein bar", category = FoodCategory.OTHER, kcal = 380.0, protein = 30.0, carbs = 40.0, fat = 10.0, isUserAdded = true)
    private val bowl = Recipe(id = 10, name = "Rice bowl", mealType = MealType.LUNCH, ingredients = listOf(RecipeIngredient("rice", 80.0)))
    private val edited = Recipe(id = 11, name = "Porridge", mealType = MealType.BREAKFAST, isUserModified = true)
    private val removed = Recipe(id = 12, name = "Lasagne alla Bolognese", mealType = MealType.DINNER, isHidden = true)
    private val mine = Recipe(id = 40, name = "Grandma's stew", mealType = MealType.DINNER, isCustom = true, ingredients = listOf(RecipeIngredient("my protein bar", 50.0)))
    private val profile = UserProfile(weightKg = 68.0, breakfastPicks = listOf(11, 40), proteinOff = listOf("pork"), dislikedIngredients = listOf("olives"))

    private fun sample() = BackupCodec.build(
        profile = profile,
        foods = listOf(rice, myBar),
        recipes = listOf(bowl, edited, removed, mine),
        pantry = listOf(PantryItem(id = 5, foodId = 2, grams = 120.0, expiresOnEpochDay = 20_400, addedOnEpochDay = 20_390)),
        barcodes = listOf(BarcodeProduct("3017620422003", "Bar", 2)),
        benchmarks = listOf(Benchmark(id = 3, epochDay = 20_000, fingerMaxAddedKg = 12.0, pullUpMaxAddedKg = 25.0, bodyWeightKg = 68.0)),
        meals = listOf(
            MealSlot(20_400, MealType.LUNCH, recipeId = 10, portions = 1.2),
            MealSlot(20_400, MealType.DINNER, recipeId = 40, portions = 1.0, status = MealStatus.COOKED),
            MealSlot(20_401, MealType.DINNER, recipeId = 99, portions = 1.0), // recipe no longer exists
        ),
        appVersion = "0.8.0-beta",
        createdAt = "2026-11-01 10:00",
    )

    @Test
    fun keepsOnlyWhatTheUserCreatedOrChanged() {
        val b = sample()
        assertEquals(listOf("my protein bar"), b.userFoods.map { it.name })
        assertEquals(setOf("Porridge", "Grandma's stew"), b.recipes.map { it.name }.toSet())
        assertEquals(listOf("Lasagne alla Bolognese"), b.removedRecipes)
        assertEquals(listOf(BackupPantryItem("my protein bar", 120.0, 20_400, 20_390)), b.pantry)
        assertEquals("my protein bar", b.barcodes.single().food)
        assertEquals(mapOf(10L to "Rice bowl", 40L to "Grandma's stew", 11L to "Porridge"), b.recipeNames)
    }

    @Test
    fun survivesEncodingRoundTrip() {
        val b = sample()
        val decoded = BackupCodec.decode(BackupCodec.encode(b))
        assertEquals(b, decoded)
        val s = BackupCodec.summary(decoded)
        assertEquals(1, s.customRecipes)
        assertEquals(1, s.editedRecipes)
        assertEquals(1, s.removedRecipes)
        assertEquals(3, s.meals)
        assertTrue(s.hasProfile)
    }

    @Test
    fun remapsIdsOnANewInstall() {
        val b = sample()
        // On the new phone bundled recipes got other ids and the custom recipe was inserted as 7.
        val resolve = BackupCodec.recipeResolver(b, customIds = mapOf(40L to 7L), idByName = mapOf("Rice bowl" to 3L, "Porridge" to 4L))
        val meals = BackupCodec.restoredMeals(b, resolve)
        assertEquals(listOf(3L, 7L), meals.map { it.recipeId })
        assertEquals(MealStatus.COOKED, meals[1].status)
        val p = BackupCodec.restoredProfile(b, resolve)!!
        assertEquals(listOf(4L, 7L), p.breakfastPicks)
        assertEquals(listOf("pork"), p.proteinOff)
        assertEquals(68.0, p.weightKg, 0.0)
    }

    @Test
    fun rejectsFilesThatAreNotBackups() {
        for (text in listOf("hello", "{\"app\":\"Other\"}", "{\"app\":\"Mango\",\"format\":99}")) {
            val error = runCatching { BackupCodec.decode(text) }.exceptionOrNull()
            assertTrue(text, error is IllegalArgumentException)
        }
        // Older or partial backups still load with defaults.
        val minimal = BackupCodec.decode("{\"app\":\"Mango\",\"format\":1}")
        assertNull(minimal.profile)
        assertFalse(BackupCodec.summary(minimal).hasProfile)
    }
}
