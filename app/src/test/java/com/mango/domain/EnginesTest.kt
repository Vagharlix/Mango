package com.mango.domain

import com.mango.data.ActivityLevel
import com.mango.data.Benchmark
import com.mango.data.Exercise
import com.mango.data.Food
import com.mango.data.FoodCategory
import com.mango.data.Goal
import com.mango.data.MealSlot
import com.mango.data.MealStatus
import com.mango.data.MealType
import com.mango.data.OpenFoodFacts
import com.mango.data.PantryItem
import com.mango.data.ProgramMode
import com.mango.data.Recipe
import com.mango.data.RecipeIngredient
import com.mango.data.Sex
import com.mango.data.UserProfile
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

class EnginesTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val assets = listOf("src/main/assets", "app/src/main/assets").map(::File).first { it.exists() }
    private val foods: List<Food> = json.decodeFromString(File(assets, "foods.json").readText())
    private val foodsByName = foods.mapIndexed { i, f -> f.copy(id = i + 1L) }.associateBy { it.name }
    private val exercises: Map<String, Exercise> =
        json.decodeFromString<List<Exercise>>(File(assets, "exercises.json").readText()).associateBy { it.id }
    private val recipes: List<Recipe> = json.decodeFromString<List<Recipe>>(File(assets, "recipes.json").readText())
        .mapIndexed { i, r -> NutritionEngine.withNutrients(r.copy(id = i + 1L), foodsByName) }

    private val profile = UserProfile(
        age = 30, heightCm = 180.0, weightKg = 70.0, sex = Sex.MALE, activityLevel = ActivityLevel.MODERATE,
        fingerMaxAddedKg = 20.0, pullUpMaxAddedKg = 30.0, programStartEpochDay = LocalDate.of(2026, 1, 5).toEpochDay(),
    )

    // ---------- Nutrition ----------

    @Test fun mifflinStJeor() {
        assertEquals(1680.0, NutritionEngine.bmr(profile), 0.01) // 700 + 1125 - 150 + 5
        assertEquals(1514.0, NutritionEngine.bmr(profile.copy(sex = Sex.FEMALE)), 0.01) // 700 + 1125 - 150 - 161
        assertEquals(1680.0 * 1.55, NutritionEngine.tdee(profile), 0.01)
    }

    @Test fun macroTargetsFollowIssnAndAcsm() {
        val t = NutritionEngine.targets(profile, Phase.MAX_STRENGTH)
        assertEquals(2.2 * 70, t.proteinG, 0.01)
        val energy = t.proteinG * 4 + t.carbsG * 4 + t.fatG * 9
        assertEquals(t.kcal, energy, 0.5)
        assertTrue(t.fatShare in 0.199..0.351)
        assertEquals(1.6 * 70, NutritionEngine.targets(profile, Phase.BASE).proteinG, 0.01)
        assertEquals(30.0, t.fiberG, 0.0)
    }

    @Test fun allRecipeIngredientsExistInCatalog() {
        assertTrue(recipes.size >= 100)
        recipes.forEach { r ->
            r.ingredients.forEach { assertNotNull("${r.name}: ${it.food}", foodsByName[it.food]) }
            assertTrue(r.name, r.perServing.kcal > 100)
        }
    }

    // ---------- Periodization ----------

    @Test fun phaseRatios() {
        assertEquals(listOf(4, 4, 3, 1), Periodization.allocateWeeks(12).values.toList())
        assertEquals(listOf(2, 2, 1, 1), Periodization.allocateWeeks(6).values.toList())
        assertEquals(listOf(9, 8, 5, 2), Periodization.allocateWeeks(24).values.toList())
        listOf(4, 5, 7, 9, 16, 30, 52).forEach { assertEquals(it, Periodization.allocateWeeks(it).values.sum()) }
    }

    @Test fun fixedScheduleHasDeloadsAndBenchmarks() {
        val p = profile.copy(programMode = ProgramMode.FIXED, programWeeks = 12)
        val weeks = Periodization.build(p, exercises, emptyList(), p.programStartEpochDay)
        assertEquals(12, weeks.size)
        weeks.forEach { w ->
            assertEquals(7, w.days.size)
            assertEquals((w.index + 1) % 4 == 0, w.isDeload)
            assertEquals(w.isDeload, w.days[6].isBenchmark)
        }
        assertEquals(Phase.BASE, weeks[0].phase)
        assertEquals(Phase.PEAK, weeks[11].phase)
        // Deload: -40 % sets on loaded work, intensity held at week-3 level.
        val maxHangsW3 = weeks[6].days[0].items.first { it.exercise.id == "fb_max_hangs_10s" }
        val maxHangsW4 = weeks[7].days[0].items.first { it.exercise.id == "fb_max_hangs_10s" }
        assertEquals(5, maxHangsW3.sets)
        assertEquals(3, maxHangsW4.sets)
        assertEquals(maxHangsW3.intensity!!, maxHangsW4.intensity!!, 1e-9)
        assertTrue(maxHangsW3.intensity!! in 0.80..0.90)
    }

    @Test fun repeatersMatchProtocol() {
        val w = Periodization.build(profile, exercises, emptyList(), profile.programStartEpochDay)[1]
        val rep = w.days[0].items.first { it.exercise.id == "fb_repeaters_73" }
        assertEquals(6, rep.reps)
        assertEquals(7, rep.workSec)
        assertEquals(3, rep.repRestSec)
        assertEquals(180, rep.setRestSec)
        assertTrue(rep.intensity!! in 0.60..0.70)
        val intervals = Periodization.intervals(rep)
        assertEquals(rep.sets * rep.reps, intervals.count { it.isWork })
    }

    @Test fun benchmarkUpdatesNextBlockOnly() {
        val start = profile.programStartEpochDay
        val bench = Benchmark(epochDay = start + 27, fingerMaxAddedKg = 30.0, pullUpMaxAddedKg = 40.0, bodyWeightKg = 70.0)
        val weeks = Periodization.build(profile, exercises, listOf(bench), start)
        fun load(w: Int) = weeks[w].days[0].items.first { it.exercise.category.name == "FINGERBOARD" }.loadText
        // Week 1: 62.5 % of (70 + 20) - 70 = -13.75 kg -> rounded to 0.5 kg
        assertEquals(-13.5, Periodization.targetAddedKg(0.625, 70.0, 20.0), 0.0)
        assertTrue(load(0), load(0).startsWith("BW − 13.5"))
        // Deload week 4 of block 1 still uses the old baseline: 67.5 % of 90 - 70 = -9.25 -> -9
        assertTrue(load(3), load(3).startsWith("BW − 9 "))
        // Block 2 (Max Strength, max hangs at 82.5 %) uses the new 30 kg benchmark: 0.825 * 100 - 70 = 12.5
        assertTrue(load(4), load(4).startsWith("BW + 12.5"))
    }

    @Test fun evergreenCyclesSixteenWeeks() {
        val weeks = Periodization.build(profile, exercises, emptyList(), profile.programStartEpochDay)
        assertEquals(32, weeks.size)
        assertEquals(Phase.BASE, weeks[16].phase)
        assertEquals(Phase.POWER_ENDURANCE, weeks[8].phase)
        assertEquals(Phase.PEAK, weeks[12].phase)
    }

    // ---------- Meal planning ----------

    private fun request(
        pantry: List<StockItem> = emptyList(),
        dislikes: List<String> = emptyList(),
        existing: List<MealSlot> = emptyList(),
        boost: Boolean = false,
        today: Long = LocalDate.of(2026, 10, 31).toEpochDay(),
    ) = PlanRequest(recipes, foodsByName, pantry, today, dislikes, false, boost, existing) {
        NutritionEngine.targets(profile, Phase.BASE)
    }

    private fun week(today: Long) = (today..today + 6).flatMap { d -> MealType.entries.map { d to it } }

    @Test fun dislikesAreStrictlyExcluded() {
        val req = request(dislikes = listOf("mushrooms", "pork", "cilantro", "nuts"))
        val plan = MealPlanner.plan(week(req.today), req)
        assertEquals(28, plan.size)
        val byId = recipes.associateBy { it.id }
        plan.forEach { s ->
            val r = byId.getValue(s.recipeId)
            r.ingredients.forEach { i ->
                val f = foodsByName.getValue(i.food)
                assertFalse("${r.name} has ${i.food}", i.food.contains("mushroom") || "pork" in f.groups || i.food == "cilantro" || "nuts" in f.groups)
            }
        }
    }

    @Test fun wholeWordDislikeMatching() {
        val peanut = foodsByName.getValue("peanut butter")
        assertFalse(MealPlanner.matchesDislike(peanut, peanut.name, "peas"))
        assertTrue(MealPlanner.matchesDislike(foodsByName["peas"], "peas", "pea"))
        assertTrue(MealPlanner.matchesDislike(foodsByName["walnuts"], "walnuts", "tree nuts"))
        assertTrue(MealPlanner.matchesDislike(foodsByName["lean beef mince"], "lean beef mince", "red meat"))
        assertFalse(MealPlanner.matchesDislike(foodsByName["coconut milk"], "coconut milk", "nuts"))
    }

    @Test fun antiRepetitionWithinThreeDays() {
        val req = request()
        val plan = MealPlanner.plan(week(req.today), req)
        plan.groupBy { it.recipeId }.values.forEach { uses ->
            val days = uses.map { it.epochDay }.sorted()
            days.zipWithNext().forEach { (a, b) -> assertTrue("repeat within 3 days", b - a > MealPlanner.ANTI_REPEAT_DAYS) }
        }
    }

    @Test fun cookedHistoryBlocksRecentRecipes() {
        val today = LocalDate.of(2026, 10, 31).toEpochDay()
        val first = MealPlanner.plan(listOf(today to MealType.DINNER), request(today = today)).single()
        val cooked = first.copy(epochDay = today - 2, status = MealStatus.COOKED)
        val again = MealPlanner.plan(listOf(today to MealType.DINNER), request(existing = listOf(cooked), today = today)).single()
        assertTrue(again.recipeId != first.recipeId)
    }

    @Test fun pantryAndExpiringItemsDriveSelection() {
        val today = LocalDate.of(2026, 10, 31).toEpochDay()
        val pantry = listOf(
            StockItem("salmon fillet", 300.0, today + 1, false),
            StockItem("sweet potato", 400.0, today + 2, true),
            StockItem("broccoli", 300.0, today + 1, true),
        )
        val slot = MealPlanner.plan(listOf(today to MealType.DINNER), request(pantry = pantry, today = today)).single()
        assertEquals("Baked Salmon with Sweet Potato & Broccoli", recipes.first { it.id == slot.recipeId }.name)
    }

    @Test fun portionsScaleToMealEnergyTarget() {
        val t = NutritionEngine.targets(profile, Phase.BASE)
        val r = recipes.first { it.mealType == MealType.DINNER }
        val p = MealPlanner.portionsFor(r, MealType.DINNER, t)
        assertEquals(t.kcal * 0.30, r.perServing.kcal * p, r.perServing.kcal * 0.125 + 1)
    }

    @Test fun skippedMealIngredientsCarryForwardInShoppingList() {
        val today = LocalDate.of(2026, 10, 31).toEpochDay()
        val r = recipes.first { it.name == "Turkey Bean Chili" }
        val byId = mapOf(r.id to r)
        val planned = MealSlot(today, MealType.DINNER, r.id, r.servings.toDouble())
        val full = MealPlanner.shoppingList(listOf(planned), byId, foodsByName, emptyList(), today)
        assertTrue(full.any { it.food.name == "turkey mince" && it.grams == 400.0 })
        // Turkey bought for a meal that was skipped stays in the pantry and is no longer needed.
        val pantry = listOf(StockItem("turkey mince", 400.0, today + 2, false))
        val next = MealPlanner.shoppingList(listOf(planned), byId, foodsByName, pantry, today)
        assertTrue(next.none { it.food.name == "turkey mince" })
        assertTrue(MealPlanner.shoppingList(listOf(planned.copy(status = MealStatus.SKIPPED)), byId, foodsByName, emptyList(), today).isEmpty())
    }

    @Test fun produceBoostPrefersExpiringProduce() {
        val today = LocalDate.of(2026, 10, 31).toEpochDay()
        val pantry = listOf(StockItem("spinach", 200.0, today + 4, true), StockItem("kale", 200.0, today + 4, true))
        val req = request(pantry = pantry, boost = true, today = today)
        val r = recipes.first { it.name == "Chickpea Spinach Curry" }
        val stock = MealPlanner.Stock(pantry)
        val t = NutritionEngine.targets(profile, Phase.BASE)
        val boosted = MealPlanner.score(r, 2.0, today, stock, req, t)
        val normal = MealPlanner.score(r, 2.0, today, stock, req.copy(produceBoost = false), t)
        assertTrue(boosted.expiring > 0 && normal.expiring == 0.0) // 4 days out: only inside the boosted window
        assertTrue(boosted.total > normal.total)
    }

    @Test fun cookingDeductsFirstExpiringFirst() {
        val r = Recipe(name = "t", mealType = MealType.LUNCH, servings = 1, ingredients = listOf(RecipeIngredient("eggs", 150.0)))
        val items = listOf(
            PantryItem(id = 1, foodId = 13, grams = 100.0, expiresOnEpochDay = 10, addedOnEpochDay = 0),
            PantryItem(id = 2, foodId = 13, grams = 300.0, expiresOnEpochDay = 20, addedOnEpochDay = 0),
        )
        val (updated, emptied) = MealPlanner.consume(items, mapOf("eggs" to 13L), r, 1.0)
        assertEquals(listOf(1L), emptied)
        assertEquals(250.0, updated.single { it.id == 2L }.grams, 1e-9)
    }

    @Test fun barcodeMatchingAndParsing() {
        val body = """{"status":1,"product":{"product_name":"Organic Rolled Oats","brands":"Acme","categories":"Cereals, Oat flakes",
            "nutriments":{"energy-kcal_100g":372,"proteins_100g":13.5,"carbohydrates_100g":58.7,"fat_100g":7,"fiber_100g":10,"vitamin-c_100g":0.002}}}"""
        val p = OpenFoodFacts.parse("1234567890123", body)!!
        assertEquals("Acme Organic Rolled Oats", p.name)
        assertEquals(2.0, p.nutrients.vitaminC, 1e-9)
        assertEquals("rolled oats", FoodMatcher.suggest(p.name + " " + p.categories, foods).first().name)
        assertEquals(null, OpenFoodFacts.parse("1", """{"status":0}"""))
    }

    @Test fun categoriesExist() {
        assertTrue(foods.any { it.category == FoodCategory.PRODUCE && it.seasonMonths.isNotEmpty() })
        assertEquals(Goal.BOULDERING, profile.goal)
    }
}
