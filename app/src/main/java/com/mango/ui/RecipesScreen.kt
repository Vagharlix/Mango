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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mango.data.MealType
import com.mango.data.Recipe
import com.mango.data.Repository
import com.mango.domain.NutritionEngine
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
fun BackHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
    }
}

@Composable
fun RecipesScreen(onOpen: (Long) -> Unit, onCreate: () -> Unit, onBack: () -> Unit) {
    val vm = mangoViewModel { RecipesViewModel(it) }
    val recipes by vm.recipes.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var type by remember { mutableStateOf<MealType?>(null) }
    var showHidden by remember { mutableStateOf(false) }
    val shown = remember(recipes, query, type, showHidden) {
        val q = query.trim().lowercase()
        recipes.filter { r ->
            (type == null || r.mealType == type) && r.isHidden == showHidden &&
                (q.isEmpty() || q in r.name.lowercase() || r.ingredients.any { q in it.food } || q in r.cuisine.lowercase())
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { BackHeader("Recipes (${recipes.size})", onBack) }
            item {
                OutlinedTextField(query, { query = it }, label = { Text("Search name, ingredient or cuisine") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item { FilterChip(selected = type == null, onClick = { type = null }, label = { Text("All") }) }
                    items(MealType.entries) { t -> FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t.label) }) }
                    item { FilterChip(selected = showHidden, onClick = { showHidden = !showHidden }, label = { Text("Hidden") }) }
                }
            }
            items(shown, key = { it.id }) { r ->
                SectionCard(Modifier.clickable { onOpen(r.id) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        if (r.isCustom) Pill("CUSTOM", Leaf)
                        if (r.isUserModified) Pill("EDITED", Sky)
                    }
                    Text("${r.mealType.label} · ${r.cuisine} · ${r.prepMinutes} min · per serving", style = MaterialTheme.typography.bodySmall)
                    MacroLine(r.perServing)
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = onCreate,
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("Create recipe") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
    }
}

@Composable
fun RecipeDetailScreen(id: Long, onEdit: (Long) -> Unit, onBack: () -> Unit) {
    val vm = mangoViewModel { RecipesViewModel(it) }
    val recipes by vm.recipes.collectAsStateWithLifecycle()
    val foods by vm.foods.collectAsStateWithLifecycle()
    val r = recipes.firstOrNull { it.id == id }
    if (r == null) {
        Column(Modifier.padding(16.dp)) { BackHeader("Recipe", onBack) }
        return
    }
    val byName = remember(foods) { foods.associateBy { it.name } }
    val n = r.perServing

    LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { BackHeader(r.name, onBack) }
        item {
            Row {
                Pill(r.mealType.label)
                Pill(r.cuisine, Sky)
                Pill("${r.prepMinutes} min", Leaf)
                if (r.isHidden) Pill("HIDDEN", Danger)
            }
        }
        item {
            SectionCard {
                Text("Per serving (recipe makes ${r.servings})", style = MaterialTheme.typography.titleMedium)
                Text("${n.kcal.fmt()} kcal · Protein ${n.protein.fmt(1)} g · Carbs ${n.carbs.fmt(1)} g · Fat ${n.fat.fmt(1)} g")
                Text(
                    "Fibre ${n.fiber.fmt(1)} g · Vitamin C ${n.vitaminC.fmt()} mg · Omega-3 ${n.omega3.fmt(2)} g",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item { Text("Ingredients", style = MaterialTheme.typography.titleMedium) }
        items(r.ingredients) { ing ->
            val ni = NutritionEngine.ingredientNutrients(ing, byName)
            Row(Modifier.fillMaxWidth()) {
                Text("${ing.grams.fmt()} g  ${ing.food}", modifier = Modifier.weight(1f))
                Text("${ni.kcal.fmt()} kcal · P ${ni.protein.fmt(1)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            HorizontalDivider()
            Text("Method", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        }
        itemsIndexed(r.steps) { i, step ->
            Row {
                Text("${i + 1}.", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 8.dp))
                Text(step)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 12.dp)) {
                Button(onClick = { onEdit(r.id) }, modifier = Modifier.weight(1f)) { Text("Edit") }
                OutlinedButton(onClick = { vm.setHidden(r, !r.isHidden) }, modifier = Modifier.weight(1f)) {
                    Text(if (r.isHidden) "Restore" else "Hide")
                }
            }
            if (r.isCustom) {
                TextButton(onClick = { vm.delete(r, onBack) }) { Text("Delete custom recipe", color = Danger) }
            }
        }
    }
}
