package com.mango.ui

import android.net.Uri
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalUriHandler
import kotlinx.coroutines.launch
import androidx.compose.material3.Button
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mango.data.MealType
import com.mango.data.Recipe
import com.mango.data.Repository
import com.mango.domain.MealPlanner
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class RecipesViewModel(repo: Repository) : MangoViewModel(repo) {
    val recipes = repo.recipes.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val foods = repo.foods.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setHidden(recipe: Recipe, hidden: Boolean) = launchSafe {
        if (hidden) repo.hideRecipe(recipe.id) else repo.setRecipeHidden(recipe.id, false)
    }

    fun delete(recipe: Recipe, onDone: () -> Unit) = launchSafe {
        repo.deleteCustomRecipe(recipe.id)
        repo.ensureWeekPlan()
        onDone()
    }
}

@Composable
fun RecipesScreen(onOpen: (Long) -> Unit, onCreate: () -> Unit, onBack: () -> Unit) {
    val vm = mangoViewModel { RecipesViewModel(it) }
    val recipes by vm.recipes.collectAsStateWithLifecycle()
    // Saveable so search and filters survive opening a recipe and coming back.
    var query by rememberSaveable { mutableStateOf("") }
    var type by rememberSaveable { mutableStateOf<MealType?>(null) }
    var showRemoved by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val removedCount = recipes.count { it.isHidden }
    val shown = remember(recipes, query, type, showRemoved) {
        val q = query.trim().lowercase()
        recipes.filter { r ->
            (type == null || r.mealType == type) && r.isHidden == showRemoved &&
                (q.isEmpty() || q in r.name.lowercase() || r.ingredients.any { q in it.food } || q in r.cuisine.lowercase())
        }
    }
    fun remove(r: Recipe) {
        vm.setHidden(r, true)
        scope.launch {
            val result = snackbar.showSnackbar("Removed “${r.name}” from your book", actionLabel = "Undo", duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) vm.setHidden(r, false)
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { BackHeader(if (showRemoved) "Removed recipes ($removedCount)" else "Recipe book (${recipes.size - removedCount})", onBack) }
            item {
                Text(
                    if (showRemoved) "These are never planned. Tap Put back to return one to your book."
                    else "Tap ⊖ to remove a recipe you don't like – it's never planned again (undo any time under Removed).",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                OutlinedTextField(query, { query = it }, label = { Text("Search name, ingredient or cuisine") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { FilterChip(selected = type == null, onClick = { type = null }, label = { Text("All") }) }
                    items(MealType.entries) { t -> FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.label) }) }
                    item { FilterChip(selected = showRemoved, onClick = { showRemoved = !showRemoved }, label = { Text("Removed ($removedCount)") }) }
                }
            }
            if (shown.isEmpty()) {
                item { Text(if (showRemoved) "No removed recipes." else "No recipe matches.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(shown, key = { it.id }) { r ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                        .clickable { onOpen(r.id) }
                        .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(r.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                            if (r.isCustom) Pill("CUSTOM", Leaf)
                            if (r.isUserModified) Pill("EDITED", Sky)
                        }
                        Text(
                            "${r.mealType.label} · ${r.cuisine} · ${r.prepMinutes} min" + if (r.batchMeals > 1) " · batch × ${r.batchMeals}" else "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        MacroLine(r.perServing)
                    }
                    if (showRemoved) {
                        TextButton(onClick = { vm.setHidden(r, false) }) { Text("Put back") }
                    } else {
                        IconButton(onClick = { remove(r) }) {
                            Icon(Icons.Filled.RemoveCircleOutline, contentDescription = "Remove from my book", tint = Faint)
                        }
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = onCreate,
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("Create recipe") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 72.dp))
    }
}

@Composable
fun RecipeDetailScreen(id: Long, initialServings: Double, swaps: Map<String, String>, onEdit: (Long) -> Unit, onBack: () -> Unit) {
    val vm = mangoViewModel { RecipesViewModel(it) }
    val recipes by vm.recipes.collectAsStateWithLifecycle()
    val foods by vm.foods.collectAsStateWithLifecycle()
    val r = recipes.firstOrNull { it.id == id }
    // Quantities are shown for what you cook: your planned portion from a meal, otherwise one person.
    var servings by rememberSaveable { mutableStateOf(initialServings.coerceIn(0.25, 12.0)) }
    if (r == null) {
        Column(Modifier.padding(16.dp)) { BackBar(onBack) }
        return
    }
    val byName = remember(foods) { foods.associateBy { it.name } }
    val uriHandler = LocalUriHandler.current
    val factor = servings / r.servings.coerceAtLeast(1)
    val n = r.perServing * servings

    LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { BackBar(onBack) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionLabel(r.mealType.label, color = MangoOrange)
                Text(r.name, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 30.sp)
                Row {
                    Pill(r.cuisine, Sky)
                    Pill("${r.prepMinutes} min", Leaf)
                    if (r.isCustom) Pill("Custom", Leaf)
                    if (r.isUserModified) Pill("Edited", Sky)
                    if (r.isHidden) Pill("Removed", Danger)
                    if (r.needsAny.isNotEmpty()) Pill(r.needsAny.joinToString(" or ") { it.label.substringBefore(" /").lowercase() }, Warn)
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp)).padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    Text("Quantities for", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(servingsText(servings), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = { servings = (servings - 0.25).coerceAtLeast(0.25) }) { Icon(Icons.Filled.Remove, contentDescription = "Fewer servings") }
                IconButton(onClick = { servings = (servings + 0.25).coerceAtMost(12.0) }) { Icon(Icons.Filled.Add, contentDescription = "More servings") }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Energy", "${n.kcal.fmt()} kcal", Modifier.weight(1f), highlight = true)
                    StatTile("Protein", "${n.protein.fmt()} g", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Carbs", "${n.carbs.fmt()} g", Modifier.weight(1f))
                    StatTile("Fat", "${n.fat.fmt()} g", Modifier.weight(1f))
                }
                Text(
                    "Fibre ${n.fiber.fmt(1)} g · Vitamin C ${n.vitaminC.fmt()} mg · Omega-3 ${n.omega3.fmt(2)} g",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item { SectionLabel("Ingredients", Modifier.padding(top = 4.dp)) }
        item {
            GroupedCard {
                val replaced = swaps.entries.groupBy({ it.value }, { it.key })
                MealPlanner.effectiveIngredients(r, swaps, byName).forEachIndexed { i, ing ->
                    if (i > 0) HorizontalDivider(color = Hairline)
                    val grams = ing.grams * factor
                    val food = byName[ing.food]
                    Row(Modifier.fillMaxWidth().padding(14.dp, 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(ing.food.replaceFirstChar { it.uppercase() }, fontSize = 15.sp)
                            replaced[ing.food]?.let { Text("instead of ${it.joinToString()} this week", fontSize = 12.sp, color = Sky) }
                        }
                        Text(
                            quantityText(roundGrams(grams), food?.unitGrams, food?.unitName),
                            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (r.source.isNotEmpty()) {
            item {
                SectionCard {
                    SectionLabel("Based on", color = Sky)
                    Text(r.source, fontSize = 14.sp)
                    TextButton(onClick = {
                        uriHandler.openUri("https://duckduckgo.com/?q=" + Uri.encode("${r.source} ${r.name} recipe"))
                    }) { Text("Look up the original (needs internet)") }
                }
            }
        }
        item { SectionLabel("Method", Modifier.padding(top = 4.dp)) }
        itemsIndexed(r.steps) { i, step ->
            Row {
                Text("${i + 1}.", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 8.dp))
                Text(step, fontSize = 15.sp, lineHeight = 21.sp)
            }
        }
        item {
            Text(
                "The recipe as written makes ${r.servings} serving${if (r.servings == 1) "" else "s"}. Liquids like broth are mostly absorbed or evaporate while cooking.",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = { onEdit(r.id) }, modifier = Modifier.weight(1f)) { Text("Edit") }
                OutlinedButton(onClick = { vm.setHidden(r, !r.isHidden) }, modifier = Modifier.weight(1f)) {
                    Text(if (r.isHidden) "Put back in my book" else "Remove from my book")
                }
            }
            if (r.isCustom) {
                TextButton(onClick = { vm.delete(r, onBack) }) { Text("Delete custom recipe", color = Danger) }
            }
        }
    }
}

/** Kitchen-friendly rounding: 1 g under 20 g, then 5 g steps. */
private fun roundGrams(g: Double): Double = if (g < 20) kotlin.math.round(g) else kotlin.math.round(g / 5) * 5
