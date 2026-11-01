package com.mango.domain

import com.mango.data.ActivityLevel
import com.mango.data.Benchmark
import com.mango.data.Equipment
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

    /** Guards against over-generous recipes: per-serving energy and typical single-adult portion caps. */
    @Test fun recipePortionsAreReasonableForOnePerson() {
        val energy = mapOf(
            MealType.BREAKFAST to 350.0..650.0, MealType.LUNCH to 450.0..750.0,
            MealType.DINNER to 450.0..800.0, MealType.SNACK to 150.0..380.0,
        )
        fun cap(food: Food): Double = when {
            food.name.endsWith("broth") -> 400.0
            food.category == FoodCategory.FAT_OIL -> 10.0
            food.name in setOf("parmesan", "cheddar") -> 30.0
            food.name in setOf("feta", "mozzarella") -> 60.0
            food.category == FoodCategory.NUT_SEED -> 35.0
            food.name == "eggs" -> 150.0
            food.category == FoodCategory.PROTEIN -> 180.0
            food.name in setOf("lentils", "red lentils") -> 90.0
            food.category == FoodCategory.GRAIN && "bread" !in food.name && "tortilla" !in food.name -> 90.0
            food.category == FoodCategory.SPICE -> 5.0
            else -> 250.0
        }
        recipes.forEach { r ->
            assertTrue("${r.name}: ${r.perServing.kcal} kcal", r.perServing.kcal in energy.getValue(r.mealType))
            r.ingredients.forEach { i ->
                val perServing = i.grams / r.servings
                val food = foodsByName.getValue(i.food)
                assertTrue("${r.name}: ${i.food} $perServing g per serving", perServing <= cap(food))
            }
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

    @Test fun guidedSessionChainsExercisesWithSetUpBreaks() {
        val day = weeksWith(Equipment.DEFAULT)[5].days[0] // Max Strength, day 1
        val all = Periodization.sessionIntervals(day.items)
        val timed = day.items.filter { it.isTimed }
        assertEquals(1, all.count { it.label == "GET READY" })
        assertEquals(timed.size - 1, all.count { it.label == "SET UP" && it.seconds == 60 })
        assertEquals(timed.sumOf { p -> Periodization.intervals(p).count { it.isWork } }, all.count { it.isWork })
        val hang = day.items.first { it.exercise.id == "fb_max_hangs_10s" }
        assertEquals("HANG", Periodization.intervals(hang)[1].label)
        assertEquals("+6.5 kg", hang.load) // 85 % of (70 + 20) - 70
        assertTrue(Periodization.estimateMinutes(day.items) in 30..120)
    }

    // ---------- Equipment ----------

    private fun weeksWith(equipment: List<Equipment>) =
        Periodization.build(profile.copy(equipment = equipment), exercises, emptyList(), profile.programStartEpochDay)

    @Test fun substitutionChainsReferenceRealExercises() {
        Periodization.SUBSTITUTES.forEach { (id, subs) ->
            assertNotNull(id, exercises[id])
            subs.forEach { assertNotNull("$id -> $it", exercises[it]) }
        }
    }

    @Test fun missingHangboardFallsBackToEdgeLiftsThenClimbing() {
        val noBoard = Equipment.DEFAULT - Equipment.FINGERBOARD
        val maxStrengthDay1 = weeksWith(noBoard)[5].days[0].items
        assertTrue(maxStrengthDay1.none { it.exercise.requires.flatten().contains(Equipment.FINGERBOARD) })
        val sub = maxStrengthDay1.first { it.substitutedFor == "Max Hangs 10 s (20 mm)" }
        assertEquals("climb_limit_bouldering", sub.exercise.id)

        val withEdge = weeksWith(noBoard + Equipment.LIFTING_EDGE)[5].days[0].items
        assertEquals("fb_edge_lift_max", withEdge.first { it.substitutedFor != null }.exercise.id)
        // No hangboard -> the max-hang test is dropped from benchmark day, the pull-up test remains.
        assertEquals(listOf("bench_pullup_1rm"), weeksWith(noBoard)[3].days[6].items.map { it.exercise.id })
    }

    @Test fun bodyweightOnlyStillProducesAFullPlan() {
        val weeks = weeksWith(emptyList())
        weeks.forEach { w ->
            w.days.forEach { d ->
                d.items.forEach { assertTrue(it.exercise.id, it.exercise.isAvailable(emptyList())) }
                if (d.dayNumber < 7) assertTrue("week ${w.index} day ${d.dayNumber}", d.items.isNotEmpty())
            }
            assertTrue(w.days[6].isRest) // no benchmark possible without equipment
        }
        assertTrue(weeks[0].days[0].items.any { it.exercise.category.name == "PULL" })
    }

    @Test fun hangLoadsAdaptWithoutWeights() {
        val noWeights = Equipment.DEFAULT - Equipment.WEIGHTS
        val hang = weeksWith(noWeights)[5].days[0].items.first { it.exercise.id == "fb_max_hangs_10s" }
        assertTrue(hang.loadText, hang.loadText.startsWith("Bodyweight"))
        assertEquals("pull_bodyweight_tempo", weeksWith(noWeights)[5].days[0].items.first { it.exercise.category.name == "PULL" }.exercise.id)
    }

    @Test fun dayTitlesReflectActualContent() {
        assertEquals("Fingers + Pull + Core", weeksWith(Equipment.DEFAULT)[0].days[0].title)
        assertEquals("Mobility & Flexibility", weeksWith(Equipment.DEFAULT)[0].days[2].title)
        assertTrue(weeksWith(Equipment.DEFAULT - Equipment.BOULDER_WALL - Equipment.ROPE_WALL)[0].days.none { it.title.contains("Climbing") })
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

    @Test fun dislikeSuggestionsOfferGroupsAndFoods() {
        assertEquals(DislikeSuggestions.COMMON.take(8), DislikeSuggestions.suggest("", foods, emptyList()).map { it.term })
        val nuts = DislikeSuggestions.suggest("nu", foods, emptyList())
        assertEquals("nuts", nuts.first().term)
        assertTrue(nuts.first().isGroup && nuts.first().foodCount == 4) // walnuts, almonds, cashews, almond butter
        val beans = DislikeSuggestions.suggest("bea", foods, listOf("black beans")).map { it.term }
        assertTrue(beans.containsAll(listOf("kidney beans", "white beans")) && "black beans" !in beans)
        // Every suggestion actually filters something when added.
        DislikeSuggestions.suggest("mush", foods, emptyList()).forEach { sug ->
            assertTrue(sug.term, recipes.any { !MealPlanner.isAllowed(it, listOf(sug.term), foodsByName) })
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

    @Test fun freshlyCookedMainsDontRepeatWithinThreeDays() {
        val req = request()
        // Leftovers and the breakfast rotation repeat on purpose; freshly cooked main meals must not.
        val plan = MealPlanner.plan(week(req.today), req).filter { !it.isLeftover && it.mealType != MealType.BREAKFAST }
        plan.groupBy { it.recipeId }.values.forEach { uses ->
            val days = uses.map { it.epochDay }.sorted()
            days.zipWithNext().forEach { (a, b) -> assertTrue("repeat within 3 days", b - a > MealPlanner.ANTI_REPEAT_DAYS) }
        }
    }

    @Test fun breakfastsRotateBetweenTwoRecipes() {
        val req = request()
        val breakfasts = MealPlanner.plan(week(req.today), req).filter { it.mealType == MealType.BREAKFAST }.sortedBy { it.epochDay }
        assertEquals(7, breakfasts.size)
        assertEquals(2, breakfasts.map { it.recipeId }.toSet().size)
        breakfasts.zipWithNext().forEach { (a, b) -> assertTrue("alternates", a.recipeId != b.recipeId) }
        assertEquals(3, MealPlanner.plan(week(req.today), req.copy(breakfastVariety = 3)).filter { it.mealType == MealType.BREAKFAST }.map { it.recipeId }.toSet().size)
    }

    @Test fun longRecipesAreBatchCookedForUpToThreeMeals() {
        val req = request()
        val byId = recipes.associateBy { it.id }
        val plan = MealPlanner.plan(week(req.today), req)
        val batches = plan.filter { it.batchId != 0L }.groupBy { it.batchId }
        assertTrue("at least two batch sessions a week", batches.size >= 2)
        batches.values.forEach { b ->
            val cook = b.single { !it.isLeftover }
            val recipe = byId.getValue(cook.recipeId)
            assertTrue(b.all { it.recipeId == cook.recipeId && it.mealType != MealType.BREAKFAST && it.mealType != MealType.SNACK })
            assertTrue(b.size in 2..recipe.batchMeals)
            b.filter { it.isLeftover }.forEach { assertTrue(it.epochDay - cook.epochDay in 1..MealPlanner.BATCH_WINDOW_DAYS) }
        }
        // Batch cooking means fewer cooking sessions than meals.
        val sessions = plan.count { it.mealType in setOf(MealType.LUNCH, MealType.DINNER) && !it.isLeftover }
        val noBatch = MealPlanner.plan(week(req.today), req.copy(batchCooking = false)).count { it.mealType in setOf(MealType.LUNCH, MealType.DINNER) }
        assertTrue("$sessions vs $noBatch", sessions <= noBatch - 3)
    }

    @Test fun shoppingListSkipsCookedBatchesAndTheSpiceRack() {
        val today = LocalDate.of(2026, 11, 2).toEpochDay()
        val curry = recipes.first { it.name == "Butternut Squash & Red Lentil Curry" }
        val byId = mapOf(curry.id to curry)
        val cook = MealSlot(today, MealType.DINNER, curry.id, 1.0, batchId = 7)
        val leftover = MealSlot(today + 1, MealType.LUNCH, curry.id, 1.0, batchId = 7, isLeftover = true)
        val before = MealPlanner.shoppingList(listOf(cook, leftover), byId, foodsByName, emptyList(), today)
        assertEquals(140.0, before.first { it.food.name == "red lentils" }.grams, 0.0) // recipe: 140 g for 2 servings = cook + leftover
        // Once the batch is cooked, its leftovers need nothing more.
        assertTrue(MealPlanner.shoppingList(listOf(cook.copy(status = MealStatus.COOKED), leftover), byId, foodsByName, emptyList(), today).isEmpty())
        // Spice-rack staples are never on the list.
        val staples = foods.filter { it.isStaple }.map { it.name }.toSet()
        val withRack = MealPlanner.shoppingList(listOf(cook, leftover), byId, foodsByName, emptyList(), today, staples)
        assertTrue(withRack.none { it.food.isStaple } && before.any { it.food.isStaple })
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

    private fun consolidatedWeek(req: PlanRequest, pantry: List<StockItem> = emptyList(), dislikes: List<String> = emptyList()): List<MealSlot> {
        val raw = MealPlanner.plan(week(req.today), req)
        val changed = MealPlanner.consolidate(raw, recipes.associateBy { it.id }, foodsByName, pantry, dislikes)
            .associateBy { it.epochDay to it.mealType }
        return raw.map { changed[it.epochDay to it.mealType] ?: it }
    }

    private fun distinctItems(plan: List<MealSlot>): Set<String> {
        val byId = recipes.associateBy { it.id }
        return plan.flatMap { s -> MealPlanner.effectiveIngredients(byId.getValue(s.recipeId), s.swaps).map { it.food } }
            .filter { foodsByName.getValue(it).category != FoodCategory.SPICE }.toSet()
    }

    @Test fun weekUsesOneMemberPerInterchangeableGroup() {
        listOf(LocalDate.of(2026, 11, 2), LocalDate.of(2027, 3, 1), LocalDate.of(2027, 7, 5)).forEach { start ->
            val items = distinctItems(consolidatedWeek(request(today = start.toEpochDay())))
            items.mapNotNull { foodsByName.getValue(it).swapGroup }.groupingBy { it }.eachCount()
                .forEach { (group, n) -> assertEquals("$start $group", 1, n) }
        }
    }

    @Test fun basketReuseShortensTheShoppingList() {
        listOf(LocalDate.of(2026, 11, 2), LocalDate.of(2027, 3, 1), LocalDate.of(2027, 7, 5)).forEach { start ->
            val req = request(today = start.toEpochDay())
            // Reference: the original planner (no basket reuse, no batch cooking, a different breakfast every day).
            val before = distinctItems(MealPlanner.plan(week(req.today), req.copy(reuseWeight = 0.0, batchCooking = false, breakfastVariety = 7))).size
            val after = distinctItems(consolidatedWeek(req)).size
            println("$start distinct items: $before -> $after")
            assertTrue("$start: $before -> $after", after <= before * 0.75)
        }
    }

    @Test fun consolidationPrefersPantryAndNeverPicksDislikes() {
        val today = LocalDate.of(2026, 11, 2).toEpochDay()
        val withChickpeas = recipes.first { it.name == "Chickpea Spinach Curry" }
        val withBlackBeans = recipes.first { it.name == "Black Bean Tacos with Mango Salsa" }
        val slots = listOf(
            MealSlot(today, MealType.DINNER, withChickpeas.id, 1.0),
            MealSlot(today + 1, MealType.DINNER, withBlackBeans.id, 1.0),
        )
        val byId = recipes.associateBy { it.id }
        val pantry = listOf(StockItem("black beans", 400.0, today + 300, false))
        val changed = MealPlanner.consolidate(slots, byId, foodsByName, pantry, emptyList())
        assertEquals(mapOf("chickpeas" to "black beans"), changed.single { it.recipeId == withChickpeas.id }.swaps)
        // A disliked member is never imposed, even if it is in the pantry.
        val respectful = MealPlanner.consolidate(slots, byId, foodsByName, pantry, listOf("black beans"))
        assertEquals(mapOf("black beans" to "chickpeas"), respectful.single { it.recipeId == withBlackBeans.id }.swaps)
        // The shopping list and pantry deduction follow the substitution.
        val list = MealPlanner.shoppingList(slots.map { s -> changed.firstOrNull { it.recipeId == s.recipeId } ?: s }, byId, foodsByName, emptyList(), today)
        assertTrue(list.none { it.food.name == "chickpeas" })
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
        assertTrue(full.any { it.food.name == "turkey mince" && it.grams == 320.0 })
        // Turkey bought for a meal that was skipped stays in the pantry and is no longer needed.
        val pantry = listOf(StockItem("turkey mince", 320.0, today + 2, false))
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
