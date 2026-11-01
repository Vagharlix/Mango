package com.mango.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mango.data.MealSlot
import com.mango.data.MealStatus
import com.mango.data.Nutrients
import com.mango.data.Recipe
import com.mango.data.Repository
import com.mango.data.UserProfile
import com.mango.domain.DailyTargets
import com.mango.domain.MealPlanner
import com.mango.domain.ShoppingItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn

data class PlannedMeal(val slot: MealSlot, val recipe: Recipe?, val batch: List<MealSlot> = emptyList()) {
    /** Nutrients actually eaten for this slot (portion-scaled). */
    val nutrients: Nutrients
        get() = recipe?.perServing?.times(slot.portions) ?: Nutrients()

    val isBatchCook: Boolean get() = slot.batchId != 0L && !slot.isLeftover

    /** Planned leftover meals this cooking session also covers. */
    val leftovers: List<MealSlot> get() = batch.filter { it.isLeftover && it.status == MealStatus.PLANNED }

    /** Portions to cook now: the whole batch for a batch-cooking meal. */
    val cookPortions: Double get() = if (isBatchCook) slot.portions + leftovers.sumOf { it.portions } else slot.portions

    val cookSlot: MealSlot? get() = batch.firstOrNull { !it.isLeftover }
}

fun mealLabel(slot: MealSlot, today: Long): String {
    val day = when (slot.epochDay) {
        today -> "today"
        today - 1 -> "yesterday"
        today + 1 -> "tomorrow"
        else -> slot.epochDay.asDate("EEE")
    }
    return "$day ${slot.mealType.label.lowercase()}"
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
        combine(repo.slots(today - MealPlanner.BATCH_WINDOW_DAYS, today + 6), repo.recipes, repo.shoppingList(window, p)) { slots, recipes, shopping ->
            val byId = recipes.associateBy { it.id }
            val days = (today..today + 6).toList()
            val batches = slots.filter { it.batchId != 0L }.groupBy { it.batchId }
            MealPlanState(
                loading = false,
                profile = p,
                today = today,
                days = days,
                meals = slots.filter { it.epochDay >= today }.groupBy { it.epochDay }.mapValues { (_, v) ->
                    v.sortedBy { it.mealType.ordinal }.map { PlannedMeal(it, byId[it.recipeId], batches[it.batchId].orEmpty()) }
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
    onOpenRecipe: (id: Long, portions: Double, swaps: Map<String, String>) -> Unit,
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
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            item {
                ScreenHeader("Meals", eyebrow = s.today.asDate("EEEE d MMM")) {
                    RoundIconButton(Icons.AutoMirrored.Filled.MenuBook, "Recipes", onClick = onOpenRecipes)
                }
            }
            if (s.isGroceryDay) {
                item {
                    HeroCard("GROCERY DAY", "Review the menu before you shop", "Swap anything you don't fancy – the shopping list updates instantly.", labelColor = Warn) {
                        PrimaryButton("Review menu & list", onOpenGrocery, icon = null)
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    s.days.forEach { d ->
                        val dayMeals = s.meals[d].orEmpty()
                        DayTile(
                            dow = if (d == s.today) "Today" else d.asDate("EEE"),
                            num = d.asDate("d"),
                            selected = d == selected,
                            dot = when {
                                dayMeals.isNotEmpty() && dayMeals.all { it.slot.status != MealStatus.PLANNED } -> Leaf
                                dayMeals.any { it.slot.status == MealStatus.SKIPPED } -> Sky
                                else -> Faint
                            },
                            onClick = { selected = d },
                            modifier = Modifier.weight(1f),
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
                    today = s.today,
                    onOpen = { m.recipe?.let { onOpenRecipe(it.id, m.cookPortions, m.slot.swaps) } },
                    onCook = { vm.cook(m.slot) },
                    onSkip = { vm.skip(m.slot) },
                    onUnskip = { vm.unskip(m.slot) },
                )
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

/** Energy and protein at a glance; the full nutrient breakdown only on demand. */
@Composable
private fun DayTotals(meals: List<PlannedMeal>, t: DailyTargets) {
    val eaten = meals.filter { it.slot.status != MealStatus.SKIPPED }.fold(Nutrients()) { acc, m -> acc + m.nutrients }
    var open by remember { mutableStateOf(false) }
    SectionCard(Modifier.clickable { open = !open }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Planned vs target", Modifier.weight(1f))
            Text(if (open) "Less" else "All nutrients", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MangoOrange)
        }
        TargetBar("Energy", eaten.kcal, t.kcal, "kcal")
        TargetBar("Protein (${t.proteinPerKg.fmt(1)} g/kg)", eaten.protein, t.proteinG, "g", Leaf)
        AnimatedVisibility(open) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TargetBar("Carbohydrate", eaten.carbs, t.carbsG, "g", Sky)
                TargetBar("Fat", eaten.fat, t.fatG, "g", Warn)
                TargetBar("Fibre", eaten.fiber, t.fiberG, "g", Leaf)
                TargetBar("Vitamin C", eaten.vitaminC, t.vitaminCMg, "mg", MangoOrange)
                TargetBar("Omega-3", eaten.omega3, t.omega3G, "g", Sky)
            }
        }
        if (meals.any { it.slot.status == MealStatus.SKIPPED }) {
            Text(
                "Meals out are just meals – no calorie compensation applied.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MealCard(meal: PlannedMeal, today: Long, onOpen: () -> Unit, onCook: () -> Unit, onSkip: () -> Unit, onUnskip: () -> Unit) {
    val r = meal.recipe
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(meal.slot.mealType.label, Modifier.weight(1f), color = MangoOrange)
            if (meal.slot.status == MealStatus.PLANNED) {
                when {
                    meal.isBatchCook && meal.leftovers.isNotEmpty() -> Pill("Batch × ${meal.leftovers.size + 1}", Leaf)
                    meal.slot.isLeftover -> Pill("Leftovers", Sky)
                }
            }
            when (meal.slot.status) {
                MealStatus.COOKED -> Pill(if (meal.slot.isLeftover) "Eaten" else "Cooked", Leaf)
                MealStatus.SKIPPED -> Pill("Dining out", Sky)
                MealStatus.PLANNED -> {}
            }
        }
        Row(Modifier.fillMaxWidth().clickable(onClick = onOpen), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(r?.name ?: "Recipe removed", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                if (r != null) {
                    Text("${servingsText(meal.slot.portions)} · ${r.prepMinutes} min · ${r.cuisine}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    MacroLine(r.perServing, meal.slot.portions)
                    swapsText(meal.slot.swaps)?.let { Text(it, fontSize = 12.sp, color = Sky) }
                    if (meal.slot.status == MealStatus.PLANNED) {
                        when {
                            meal.isBatchCook && meal.leftovers.isNotEmpty() -> Text(
                                "Cook ${servingsText(meal.cookPortions)} – also covers " + meal.leftovers.joinToString(", ") { mealLabel(it, today) },
                                fontSize = 12.sp, color = Leaf,
                            )
                            meal.slot.isLeftover -> Text(
                                meal.cookSlot?.let { "From ${mealLabel(it, today)} – just reheat" } ?: "Cook fresh (batch was re-planned)",
                                fontSize = 12.sp, color = Sky,
                            )
                        }
                    }
                }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Faint)
        }
        when (meal.slot.status) {
            MealStatus.PLANNED -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onCook, modifier = Modifier.weight(1f)) { Text(if (meal.slot.isLeftover && meal.cookSlot != null) "Eaten" else "Cooked") }
                OutlinedButton(onClick = onSkip, modifier = Modifier.weight(1f)) { Text("Skip / Dining out") }
            }
            MealStatus.SKIPPED -> TextButton(onClick = onUnskip) { Text("Undo skip") }
            MealStatus.COOKED -> {}
        }
    }
}

/** "Uses chickpeas instead of black beans" – substitutions that keep the week's shopping list short. */
fun swapsText(swaps: Map<String, String>): String? =
    if (swaps.isEmpty()) null else "Uses " + swaps.entries.joinToString(", ") { "${it.value} instead of ${it.key}" }

fun servingsText(servings: Double): String {
    val n = if (servings % 1.0 == 0.0) servings.fmt() else servings.fmt(2).trimEnd('0').trimEnd('.')
    return "$n serving" + if (servings == 1.0) "" else "s"
}
