package com.mango.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mango.data.MealSlot
import com.mango.data.MealStatus
import com.mango.data.Nutrients
import com.mango.data.Recipe
import com.mango.data.Repository
import com.mango.data.UserProfile
import com.mango.domain.DailyTargets
import com.mango.domain.ShoppingItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

data class PlannedMeal(val slot: MealSlot, val recipe: Recipe?) {
    /** Nutrients actually eaten for this slot (portion-scaled). */
    val nutrients: Nutrients
        get() = recipe?.perServing?.times(slot.portions) ?: Nutrients()
}

data class MealPlanState(
    val loading: Boolean = true,
    val profile: UserProfile? = null,
    val today: Long = 0,
    val days: List<Long> = emptyList(),
    val meals: Map<Long, List<PlannedMeal>> = emptyMap(),
    val targets: Map<Long, DailyTargets> = emptyMap(),
    val shopping: List<ShoppingItem> = emptyList(),
    val shoppingUntil: Long = 0,
    val isGroceryDay: Boolean = false,
)

/** Shared by the Meals and Grocery screens. */
@OptIn(ExperimentalCoroutinesApi::class)
class MealPlanViewModel(repo: Repository) : MangoViewModel(repo) {
    private val today = repo.today()
    private val rejected = mutableSetOf<Long>()

    init {
        launchSafe { repo.ensureWeekPlan() }
    }

    val state = repo.profile.flatMapLatest { p ->
        if (p == null) return@flatMapLatest flowOf(MealPlanState(loading = false, today = today))
        val window = repo.shoppingWindow(p, today)
        combine(repo.slots(today, today + 6), repo.recipes, repo.shoppingList(window)) { slots, recipes, shopping ->
            val byId = recipes.associateBy { it.id }
            val days = (today..today + 6).toList()
            MealPlanState(
                loading = false,
                profile = p,
                today = today,
                days = days,
                meals = slots.groupBy { it.epochDay }.mapValues { (_, v) ->
                    v.sortedBy { it.mealType.ordinal }.map { PlannedMeal(it, byId[it.recipeId]) }
                },
                targets = days.associateWith { repo.targetsFor(p, it) },
                shopping = shopping,
                shoppingUntil = window.last,
                isGroceryDay = java.time.LocalDate.ofEpochDay(today).dayOfWeek.value == p.groceryDay,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MealPlanState(today = today))

    fun cook(slot: MealSlot) = launchSafe {
        repo.cook(slot)
        toast("Enjoy! Ingredients deducted from your pantry.")
    }

    fun skip(slot: MealSlot) = launchSafe {
        repo.skip(slot)
        toast("Enjoy your meal out – no compensation needed. Upcoming meals now prioritise expiring produce.")
    }

    fun unskip(slot: MealSlot) = launchSafe { repo.unskip(slot) }

    fun swap(slot: MealSlot) = launchSafe {
        rejected += slot.recipeId
        repo.swap(slot, rejected)
    }

    fun hideRecipe(slot: MealSlot) = launchSafe {
        repo.hideRecipe(slot.recipeId)
        toast("Recipe hidden – you can restore it from the recipe list.")
    }

    fun dislikeIngredient(term: String) = launchSafe {
        repo.addDislike(term)
        toast("\"$term\" added to dislikes – menu and shopping list updated.")
    }

    fun regenerate() = launchSafe {
        rejected.clear()
        repo.regenerateWeek()
    }

    fun markBought(item: ShoppingItem) = launchSafe { repo.markBought(item) }
}

@Composable
fun MealsScreen(
    onOpenRecipe: (Long) -> Unit,
    onOpenRecipes: () -> Unit,
    onOpenGrocery: () -> Unit,
    onSetupProfile: () -> Unit,
) {
    val vm = mangoViewModel { MealPlanViewModel(it) }
    val s by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }
    if (s.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    if (s.profile == null) {
        EmptyProfile(onSetupProfile)
        return
    }

    var selected by remember { mutableLongStateOf(s.today) }
    val meals = s.meals[selected].orEmpty()
    val target = s.targets[selected]

    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Meals", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onOpenRecipes) {
                        Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null)
                        Text("  Recipes")
                    }
                }
            }
            if (s.isGroceryDay) {
                item {
                    SectionCard {
                        Text("🛒 It's grocery day", style = MaterialTheme.typography.titleMedium, color = Warn)
                        Text("Review the 7-day menu and swap anything you don't fancy before the shopping list is final.")
                        Button(onClick = onOpenGrocery) { Text("Review menu & shopping list") }
                    }
                }
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(s.days) { d ->
                        FilterChip(
                            selected = d == selected,
                            onClick = { selected = d },
                            label = { Text(if (d == s.today) "Today" else d.asDate("EEE d")) },
                        )
                    }
                }
            }
            if (target != null) item { DayTotals(meals, target) }
            if (meals.isEmpty()) {
                item {
                    Text(
                        "No meals planned. If all recipes are excluded by your dislikes, edit them in your profile.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(meals, key = { "${it.slot.epochDay}-${it.slot.mealType}" }) { m ->
                MealCard(
                    meal = m,
                    onOpen = { m.recipe?.let { onOpenRecipe(it.id) } },
                    onCook = { vm.cook(m.slot) },
                    onSkip = { vm.skip(m.slot) },
                    onUnskip = { vm.unskip(m.slot) },
                )
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun DayTotals(meals: List<PlannedMeal>, t: DailyTargets) {
    val eaten = meals.filter { it.slot.status != MealStatus.SKIPPED }.fold(Nutrients()) { acc, m -> acc + m.nutrients }
    SectionCard {
        Text("Planned vs target", style = MaterialTheme.typography.titleMedium)
        TargetBar("Energy", eaten.kcal, t.kcal, "kcal")
        TargetBar("Protein (${t.proteinPerKg.fmt(1)} g/kg)", eaten.protein, t.proteinG, "g", Leaf)
        TargetBar("Carbohydrate", eaten.carbs, t.carbsG, "g", Sky)
        TargetBar("Fat", eaten.fat, t.fatG, "g", Warn)
        TargetBar("Fibre", eaten.fiber, t.fiberG, "g", Leaf)
        TargetBar("Vitamin C", eaten.vitaminC, t.vitaminCMg, "mg", MangoOrange)
        TargetBar("Omega-3", eaten.omega3, t.omega3G, "g", Sky)
        if (meals.any { it.slot.status == MealStatus.SKIPPED }) {
            Text(
                "Meals out are just meals – no calorie compensation applied.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MealCard(meal: PlannedMeal, onOpen: () -> Unit, onCook: () -> Unit, onSkip: () -> Unit, onUnskip: () -> Unit) {
    val r = meal.recipe
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(meal.slot.mealType.label.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
            when (meal.slot.status) {
                MealStatus.COOKED -> Pill("COOKED", Leaf)
                MealStatus.SKIPPED -> Pill("DINING OUT", Sky)
                MealStatus.PLANNED -> {}
            }
        }
        Column(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
            Text(r?.name ?: "Recipe removed", style = MaterialTheme.typography.titleMedium)
            if (r != null) {
                Text("${meal.slot.portions.fmt(2)} serving(s) · ${r.cuisine} · ${r.prepMinutes} min", style = MaterialTheme.typography.bodySmall)
                MacroLine(r.perServing, meal.slot.portions)
            }
        }
        when (meal.slot.status) {
            MealStatus.PLANNED -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCook, modifier = Modifier.weight(1f)) { Text("Cooked") }
                OutlinedButton(onClick = onSkip, modifier = Modifier.weight(1f)) { Text("Skip / Dining out") }
            }
            MealStatus.SKIPPED -> TextButton(onClick = onUnskip) { Text("Undo skip") }
            MealStatus.COOKED -> {}
        }
    }
}
