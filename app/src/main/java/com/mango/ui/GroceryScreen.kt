package com.mango.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.ThumbDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mango.data.Food
import com.mango.data.MealStatus
import com.mango.domain.FoodPreferences
import com.mango.data.Recipe
import com.mango.domain.ShoppingItem
import kotlin.math.ceil

/** Pre-grocery loop: review the 7-day menu, swap/dislike, and watch the shopping list update live. */
@Composable
fun GroceryScreen(onOpenRecipe: (id: Long, portions: Double, swaps: Map<String, String>) -> Unit, onOpenFoodPrefs: () -> Unit) {
    val vm = mangoViewModel { MealPlanViewModel(it) }
    val s by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var dislikeFor by remember { mutableStateOf<Recipe?>(null) }
    var replacing by remember { mutableStateOf<Food?>(null) }
    val allFoods by vm.allFoods.collectAsStateWithLifecycle()
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
    val profile = s.profile ?: run {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) { Text("Create your profile first.") }
        return
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                val eyebrow = if (s.isGroceryDay) {
                    "Grocery day – finalise before you shop"
                } else {
                    "Next shop: " + java.time.DayOfWeek.of(profile.groceryDay).getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
                }
                ScreenHeader("Grocery", eyebrow = eyebrow) {
                    RoundIconButton(Icons.Filled.Tune, "Food preferences", onClick = onOpenFoodPrefs)
                    RoundIconButton(Icons.Filled.Refresh, "Regenerate week", onClick = vm::regenerate)
                }
            }
            item {
                Text(
                    "Swap a meal, or tap the thumb to hide a recipe or dislike one of its ingredients. Macros and the shopping list update instantly.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item { SectionLabel("Menu · next 7 days") }
            items(s.days) { day ->
                SectionCard {
                    SectionLabel(if (day == s.today) "Today" else day.asDate(), color = MangoOrange)
                    s.meals[day].orEmpty().forEach { m ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable { m.recipe?.let { onOpenRecipe(it.id, m.cookPortions, m.slot.swaps) } }) {
                                Text(m.slot.mealType.label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(m.recipe?.name ?: "—", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${m.nutrients.kcal.fmt()} kcal · P ${m.nutrients.protein.fmt()} g" + when {
                                        m.slot.isLeftover -> " · leftovers"
                                        m.isBatchCook && m.leftovers.isNotEmpty() -> " · batch × ${m.leftovers.size + 1}"
                                        else -> ""
                                    } + when (m.slot.status) {
                                        MealStatus.COOKED -> " · cooked"
                                        MealStatus.SKIPPED -> " · dining out"
                                        MealStatus.PLANNED -> ""
                                    },
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (m.slot.status == MealStatus.PLANNED) {
                                IconButton(onClick = { vm.swap(m.slot) }) { Icon(Icons.Filled.SwapHoriz, contentDescription = "Swap") }
                                var menu by remember { mutableStateOf(false) }
                                Box {
                                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.ThumbDown, contentDescription = "Don't like") }
                                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                        DropdownMenuItem(text = { Text("Remove recipe from my book") }, onClick = {
                                            menu = false
                                            vm.hideRecipe(m.slot)
                                        })
                                        DropdownMenuItem(text = { Text("Dislike an ingredient…") }, onClick = {
                                            menu = false
                                            dislikeFor = m.recipe
                                        })
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                SectionLabel("Shopping list", Modifier.padding(top = 8.dp))
                Text(
                    "Covers meals until ${s.shoppingUntil.asDate()}. Pantry stock (including food left over from skipped meals) is already subtracted. Tick an item to add it to your pantry.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (s.shopping.isEmpty()) {
                item { Text("Nothing to buy – your pantry covers the plan. Zero waste!", color = Leaf) }
            }
            val replacements = profile.manualSwaps.takeIf { s.today <= profile.manualSwapsUntil }.orEmpty()
            if (replacements.isNotEmpty()) {
                item {
                    SectionCard {
                        SectionLabel("Replaced this week", color = Sky)
                        replacements.forEach { (from, to) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("$to instead of $from", fontSize = 14.sp, modifier = Modifier.weight(1f))
                                TextButton(onClick = { vm.clearReplacement(from) }) { Text("Undo") }
                            }
                        }
                    }
                }
            }
            items(s.shopping, key = { it.food.id }) { item ->
                ShoppingRow(
                    item = item,
                    canReplace = FoodPreferences.replacementsFor(item.food, allFoods).isNotEmpty(),
                    onReplace = { replacing = item.food },
                    onBought = { vm.markBought(item) },
                )
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    dislikeFor?.let { recipe -> DislikeIngredientDialog(recipe, onDismiss = { dislikeFor = null }) { term ->
        vm.dislikeIngredient(term)
        dislikeFor = null
    } }

    replacing?.let { food ->
        AlertDialog(
            onDismissRequest = { replacing = null },
            title = { Text("Can't find ${food.name}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Pick a replacement for this week's meals:", style = MaterialTheme.typography.bodySmall)
                    FoodPreferences.replacementsFor(food, allFoods).forEach { alt ->
                        Text(
                            alt.name.replaceFirstChar { it.uppercase() },
                            modifier = Modifier.fillMaxWidth().clickable {
                                vm.replace(food.name, alt.name)
                                replacing = null
                            }.padding(vertical = 12.dp),
                            fontSize = 16.sp,
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { replacing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ShoppingRow(item: ShoppingItem, canReplace: Boolean, onReplace: () -> Unit, onBought: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onBought), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = false, onCheckedChange = { onBought() })
        Column(Modifier.weight(1f)) {
            Text(item.food.name.replaceFirstChar { it.uppercase() })
            Text(quantityText(item.grams, item.food.unitGrams, item.food.unitName), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            item.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Leaf) }
        }
        if (canReplace) TextButton(onClick = onReplace) { Text("Can't find it?", fontSize = 12.sp) }
    }
}

fun quantityText(grams: Double, unitGrams: Double?, unitName: String?): String {
    val base = if (grams >= 1000) "${(grams / 1000).fmt(2)} kg" else "${grams.fmt()} g"
    // A little over a whole unit still counts as one (370 g of fillets is 2 breasts, not 3).
    return if (unitGrams != null && unitName != null) "$base (≈ ${maxOf(1, ceil(grams / unitGrams - 0.15).toInt())} $unitName)" else base
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DislikeIngredientDialog(recipe: Recipe, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Which ingredient?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Every recipe containing it will be excluded from now on.", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    recipe.ingredients.map { it.food }.distinct().forEach { name ->
                        AssistChip(onClick = { onPick(name) }, label = { Text(name) })
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
