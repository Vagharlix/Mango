package com.mango.domain

import com.mango.data.Food
import com.mango.data.KitchenTool
import com.mango.data.MealSlot
import com.mango.data.MealType
import com.mango.data.PantryItem
import com.mango.data.Recipe
import com.mango.data.RecipeIngredient
import com.mango.data.UserProfile
import com.mango.data.WholeChickenMode
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

class WholeChickenTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val assets = listOf("src/main/assets", "app/src/main/assets").map(::File).first { it.exists() }
    private val foods: List<Food> = json.decodeFromString<List<Food>>(File(assets, "foods.json").readText()).mapIndexed { i, f -> f.copy(id = i + 1L) }
    private val byName = foods.associateBy { it.name }
    private val recipes: List<Recipe> = json.decodeFromString<List<Recipe>>(File(assets, "recipes.json").readText())
        .mapIndexed { i, r -> NutritionEngine.withNutrients(r.copy(id = i + 1L), byName) }
    private val profile = UserProfile(age = 30, heightCm = 180.0, weightKg = 70.0)
    private val today = LocalDate.of(2026, 11, 1).toEpochDay()
    private val kg = 1.6

    private fun item(name: String, grams: Double) = ShoppingItem(byName.getValue(name), grams)

    @Test fun birdPartsAreFoodsAndAddUp() {
        WholeChicken.PARTS.forEach { assertNotNull(it.food, byName[it.food]) }
        assertNotNull(byName[WholeChicken.WHOLE])
        assertNotNull(byName[WholeChicken.STOCK])
        assertTrue(WholeChicken.PARTS.sumOf { it.share } <= 1.0)
        // A 1.6 kg bird: ~370 g of breast fillets, 2 thighs, 2 drumsticks, 2 wings and a ~400 g frame.
        assertEquals(184.0, WholeChicken.pieceGrams(WholeChicken.part(WholeChicken.BREAST)!!, kg), 1.0)
        assertEquals("2 breast fillets, 2 thighs, 2 drumsticks, 2 wings and 1 frame for stock", WholeChicken.describe(1))
    }

    @Test fun rawPoultryAndMinceKeepTwoDaysInTheFridge() {
        listOf("chicken breast", "chicken thigh", "chicken drumstick", "chicken wings", "whole chicken", "turkey mince", "lean beef mince", "pork mince")
            .forEach { assertTrue(it, byName.getValue(it).shelfLifeDays <= 2) }
    }

    @Test fun aBreastIsABreast() {
        assertEquals(1, WholeChicken.birdsFor(mapOf(WholeChicken.BREAST to 460.0), kg))
        assertEquals(2, WholeChicken.birdsFor(mapOf(WholeChicken.BREAST to 600.0), kg))
        assertEquals(1, WholeChicken.birdsFor(mapOf(WholeChicken.THIGH to 100.0, WholeChicken.DRUMSTICK to 200.0), kg))
        assertEquals(2, WholeChicken.pieces(WholeChicken.THIGH, 190.0, kg))
        assertEquals(1, WholeChicken.pieces(WholeChicken.THIGH, 20.0, kg))
    }

    @Test fun shoppingListBuysWholeBirdsWhenTheyFit() {
        val onePot = listOf(item("chicken breast", 370.0), item("chicken thigh", 200.0), item("chicken drumstick", 200.0), item("chicken wings", 160.0), item("onion", 100.0))
        val birds = WholeChicken.toBirds(onePot, WholeChickenMode.WHEN_IT_FITS, kg, byName[WholeChicken.WHOLE])
        assertEquals(listOf("whole chicken", "onion"), birds.map { it.food.name }.sortedByDescending { it.startsWith("whole") })
        val bird = birds.single { it.food.name == WholeChicken.WHOLE }
        assertEquals(1600.0, bird.grams, 0.1)
        assertTrue(bird.note!!.contains("2 thighs"))
        // One breast for one meal isn't worth a bird... unless the user always wants one.
        val small = listOf(item("chicken breast", 150.0))
        assertEquals(small, WholeChicken.toBirds(small, WholeChickenMode.WHEN_IT_FITS, kg, byName[WholeChicken.WHOLE]))
        assertEquals(WholeChicken.WHOLE, WholeChicken.toBirds(small, WholeChickenMode.ALWAYS, kg, byName[WholeChicken.WHOLE]).single().food.name)
        assertEquals(onePot, WholeChicken.toBirds(onePot, WholeChickenMode.OFF, kg, byName[WholeChicken.WHOLE]))
        // A bigger bird is bought as such.
        assertEquals(2000.0, WholeChicken.toBirds(onePot, WholeChickenMode.ALWAYS, 2.0, byName[WholeChicken.WHOLE]).single { it.food.name == WholeChicken.WHOLE }.grams, 0.1)
    }

    @Test fun piecesEatenSoonStayInTheFridgeTheRestIsFrozen() {
        val demand = mapOf(WholeChicken.BREAST to listOf(today to 150.0, today + 4 to 150.0), WholeChicken.DRUMSTICK to listOf(today + 1 to 200.0))
        val lots = WholeChicken.allocate(1, kg, today, demand)
        fun pieces(food: String, frozen: Boolean) = lots.filter { it.food == food && it.frozen == frozen }.sumOf { it.pieces }
        assertEquals(1, pieces(WholeChicken.BREAST, false))
        assertEquals(1, pieces(WholeChicken.BREAST, true))
        assertEquals(2, pieces(WholeChicken.DRUMSTICK, false))
        assertEquals(2, pieces(WholeChicken.THIGH, true))
        assertEquals(1, pieces(WholeChicken.FRAME, false))
        assertEquals(kg * 1000 * WholeChicken.PARTS.sumOf { it.share }, lots.sumOf { it.grams }, 1.0)
    }

    private fun plan(pantry: List<StockItem>, start: Long, kitchen: Set<KitchenTool> = emptySet()) =
        MealPlanner.plan(
            (start..start + 6).flatMap { d -> MealType.entries.map { d to it } },
            PlanRequest(recipes, byName, pantry, start, emptyList(), kitchen = kitchen) { NutritionEngine.targets(profile, Phase.BASE) },
        )

    @Test fun theWeekIsBuiltAroundTheBird() {
        val byId = recipes.associateBy { it.id }
        listOf(LocalDate.of(2026, 11, 2), LocalDate.of(2027, 3, 1), LocalDate.of(2027, 7, 5)).forEach { d ->
            val start = d.toEpochDay()
            val withBird = plan(WholeChicken.virtualStock(kg, start, start + 6), start)
            val used = WholeChicken.meatUsed(withBird, byId, byName, start..start + 6)
            assertTrue("$d uses ${used.toInt()} g of the bird", WholeChicken.fits(used, kg))
        }
    }

    @Test fun fryerRecipesNeedAFryer() {
        val ayam = recipes.first { it.name.startsWith("Ayam Goreng") }
        assertEquals(listOf(KitchenTool.DEEP_FRYER, KitchenTool.AIR_FRYER), ayam.needsAny)
        assertFalse(MealPlanner.hasTools(ayam, emptySet()))
        assertTrue(MealPlanner.hasTools(ayam, setOf(KitchenTool.AIR_FRYER)))
        listOf(LocalDate.of(2026, 11, 2), LocalDate.of(2027, 7, 5)).forEach { d ->
            val start = d.toEpochDay()
            assertTrue(plan(WholeChicken.virtualStock(kg, start, start + 6), start).none { it.recipeId == ayam.id })
        }
    }

    @Test fun homemadeStockStandsInForBroth() {
        val stock = MealPlanner.Stock(listOf(StockItem(WholeChicken.STOCK, 500.0, today + 3, false)))
        assertEquals(500.0, stock.available("chicken broth", today), 0.1)
        val soup = Recipe(id = 1, name = "Soup", mealType = MealType.LUNCH, servings = 1, ingredients = listOf(RecipeIngredient("chicken broth", 300.0)))
        stock.reserve(soup, 1.0, today)
        assertEquals(200.0, stock.available("chicken broth", today), 0.1)
        // Shopping: nothing to buy; cooking takes the homemade stock before any carton.
        val pantry = listOf(StockItem(WholeChicken.STOCK, 500.0, today + 3, false))
        val slot = MealSlot(today, MealType.LUNCH, 1, 1.0)
        assertTrue(MealPlanner.shoppingList(listOf(slot), mapOf(1L to soup), byName, pantry, today).isEmpty())
        val homemade = byName.getValue(WholeChicken.STOCK)
        val carton = byName.getValue("chicken broth")
        val lots = listOf(PantryItem(1, carton.id, 1000.0, today + 200, today), PantryItem(2, homemade.id, 500.0, today + 3, today))
        val (updated, _) = MealPlanner.consume(lots, byName.mapValues { it.value.id }, soup, 1.0)
        assertEquals(200.0, updated.single { it.id == 2L }.grams, 0.1)
        assertTrue(updated.none { it.id == 1L })
    }

    @Test fun frozenFoodIsUsableFromTomorrow() {
        val stock = MealPlanner.Stock(listOf(StockItem(WholeChicken.BREAST, 184.0, today + 270, false, availableFrom = today + 1)))
        assertEquals(0.0, stock.available(WholeChicken.BREAST, today), 0.1)
        assertEquals(184.0, stock.available(WholeChicken.BREAST, today + 1), 0.1)
    }

    @Test fun prepTasksBreakDownMakeStockThawAndFreeze() {
        val f = byName
        val lots = listOf(
            KitchenPrep.PantryLot(1, f.getValue(WholeChicken.BREAST), 184.0, today + 2, false, today),
            KitchenPrep.PantryLot(2, f.getValue(WholeChicken.BREAST), 184.0, today + 270, true, today),
            KitchenPrep.PantryLot(3, f.getValue(WholeChicken.FRAME), 400.0, today + 2, false, today),
            KitchenPrep.PantryLot(4, f.getValue(WholeChicken.THIGH), 200.0, today + 270, true, today),
            KitchenPrep.PantryLot(5, f.getValue("pork tenderloin"), 300.0, today + 1, false, today - 3),
        )
        // Breast today and tomorrow (the second one is in the freezer); nothing planned for the pork.
        val demand = mapOf(WholeChicken.BREAST to listOf(today to 150.0, today + 1 to 150.0))
        val tasks = KitchenPrep.tasks(today, lots, demand, breakdownOn = today, kg = kg, kitchen = setOf(KitchenTool.PRESSURE_COOKER)) { "tomorrow" }
        val breakdown = tasks.filterIsInstance<KitchenPrep.Breakdown>().single()
        assertEquals(1, breakdown.birds)
        assertTrue(breakdown.freezer.contains("2 thighs"))
        val thaw = tasks.filterIsInstance<KitchenPrep.Thaw>().single().items.single()
        assertEquals(2L, thaw.lotId)
        assertEquals("1 breast fillet", thaw.label)
        val stock = tasks.filterIsInstance<KitchenPrep.MakeStock>().single()
        assertEquals(800, stock.yieldMl)
        assertTrue(stock.pressureCooker)
        val freeze = tasks.filterIsInstance<KitchenPrep.Freeze>().single().items.single()
        assertEquals(5L, freeze.lotId)
        // Nothing to do once the bird is broken down and the frame is in the pot.
        val later = KitchenPrep.tasks(today, lots.filter { it.id != 3L && it.id != 5L }, emptyMap(), 0, kg, emptySet()) { "tomorrow" }
        assertTrue(later.isEmpty())
    }

    @Test fun oneBirdFeedsSeveralMealsAcrossBothStrategies() {
        // One-pot recipes take the whole jointed bird and cover 4 meals; spread recipes use single parts.
        val onePot = recipes.filter { r -> WholeChicken.PARTS.filter { it.food != WholeChicken.FRAME }.all { p -> r.ingredients.any { it.food == p.food } } }
        assertTrue(onePot.map { it.name }.toString(), onePot.size >= 4)
        onePot.forEach { assertEquals(it.name, 4, it.batchMeals) }
        assertTrue(recipes.any { r -> r.ingredients.any { it.food == WholeChicken.FRAME } })
        assertTrue(recipes.any { r -> r.ingredients.any { it.food == WholeChicken.DRUMSTICK } && r.ingredients.none { it.food == WholeChicken.BREAST } })
        assertTrue(recipes.any { r -> r.ingredients.any { it.food == WholeChicken.WINGS } && r.mealType == MealType.SNACK })
    }
}
