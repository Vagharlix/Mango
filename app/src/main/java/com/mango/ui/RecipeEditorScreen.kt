package com.mango.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mango.data.Food
import com.mango.data.MealType
import com.mango.data.Recipe
import com.mango.data.RecipeIngredient
import com.mango.data.Repository
import com.mango.domain.NutritionEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class RecipeEditorViewModel(repo: Repository, private val id: Long) : MangoViewModel(repo) {
    private val _draft = MutableStateFlow(Recipe(name = "", mealType = MealType.DINNER, servings = 2, cuisine = "Home"))
    val draft = _draft.asStateFlow()
    private val _step = MutableStateFlow(0)
    val step = _step.asStateFlow()
    val foods = repo.foods.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        if (id != 0L) viewModelScope.launch { repo.getRecipe(id)?.let { _draft.value = it } }
    }

    fun edit(transform: (Recipe) -> Recipe) {
        _draft.value = transform(_draft.value)
    }

    fun addIngredient(food: Food) = edit { it.copy(ingredients = it.ingredients + RecipeIngredient(food.name, food.unitGrams ?: 100.0)) }

    fun setGrams(index: Int, grams: Double) = edit { r ->
        r.copy(ingredients = r.ingredients.mapIndexed { i, ing -> if (i == index) ing.copy(grams = grams) else ing })
    }

    fun swapIngredient(index: Int, food: Food) = edit { r ->
        r.copy(ingredients = r.ingredients.mapIndexed { i, ing -> if (i == index) ing.copy(food = food.name) else ing })
    }

    fun removeIngredient(index: Int) = edit { r -> r.copy(ingredients = r.ingredients.filterIndexed { i, _ -> i != index }) }

    fun goTo(step: Int) {
        _step.value = step.coerceIn(0, STEPS.lastIndex)
    }

    fun save(onDone: () -> Unit) = launchSafe {
        val r = _draft.value
        repo.saveRecipe(r.copy(name = r.name.trim(), steps = r.steps.map { it.trim() }.filter { it.isNotEmpty() }))
        toast("Recipe saved")
        onDone()
    }

    companion object {
        val STEPS = listOf("Basics", "Ingredients", "Method", "Review")
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecipeEditorScreen(id: Long, onDone: () -> Unit) {
    val vm = mangoViewModel(key = "editor-$id") { RecipeEditorViewModel(it, id) }
    val r by vm.draft.collectAsStateWithLifecycle()
    val step by vm.step.collectAsStateWithLifecycle()
    val foods by vm.foods.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val byName = remember(foods) { foods.associateBy { it.name } }
    // Live on-device macro summation per serving.
    val perServing = NutritionEngine.total(r.ingredients, byName) * (1.0 / r.servings.coerceAtLeast(1))
    var swapIndex by remember { mutableStateOf<Int?>(null) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BackHeader(if (id == 0L) "Create recipe" else "Edit recipe", onDone)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RecipeEditorViewModel.STEPS.forEachIndexed { i, label ->
                FilterChip(selected = step == i, onClick = { vm.goTo(i) }, label = { Text("${i + 1}. $label") })
            }
        }
        SectionCard {
            Text("Per serving (live)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            MacroLine(perServing)
            Text(
                "Vitamin C ${perServing.vitaminC.fmt()} mg · Omega-3 ${perServing.omega3.fmt(2)} g",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (step) {
                0 -> {
                    OutlinedTextField(r.name, { v -> vm.edit { it.copy(name = v) } }, label = { Text("Recipe name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(r.cuisine, { v -> vm.edit { it.copy(cuisine = v) } }, label = { Text("Cuisine") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        MealType.entries.forEach { t ->
                            FilterChip(selected = r.mealType == t, onClick = { vm.edit { it.copy(mealType = t) } }, label = { Text(t.label) })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField("Servings", r.servings.toDouble(), { v -> vm.edit { it.copy(servings = v.toInt().coerceIn(1, 12)) } }, Modifier.weight(1f))
                        NumberField("Prep time", r.prepMinutes.toDouble(), { v -> vm.edit { it.copy(prepMinutes = v.toInt()) } }, Modifier.weight(1f), suffix = "min")
                    }
                    if (r.mealType == MealType.LUNCH || r.mealType == MealType.DINNER) {
                        Text("Batch cooking: one cooking session covers", style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(1 to "1 meal", 2 to "2 meals", 3 to "3 meals", 4 to "4 meals").forEach { (n, label) ->
                                FilterChip(selected = r.batchMeals == n, onClick = { vm.edit { it.copy(batchMeals = n) } }, label = { Text(label) })
                            }
                        }
                        Text(
                            "Leftovers are planned within the next 2 days (cooked food keeps 3-4 days in the fridge).",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                1 -> {
                    r.ingredients.forEachIndexed { i, ing ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(ing.food)
                                val n = NutritionEngine.ingredientNutrients(ing, byName)
                                Text("${n.kcal.fmt()} kcal · P ${n.protein.fmt(1)} g", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            NumberField("g", ing.grams, { vm.setGrams(i, it) }, Modifier.width(96.dp))
                            IconButton(onClick = { swapIndex = i }) { Icon(Icons.Filled.SwapHoriz, contentDescription = "Swap ingredient") }
                            IconButton(onClick = { vm.removeIngredient(i) }) { Icon(Icons.Filled.Delete, contentDescription = "Remove") }
                        }
                    }
                    Text("Add ingredient", style = MaterialTheme.typography.titleMedium)
                    FoodSearch(foods, onPick = vm::addIngredient)
                }
                2 -> {
                    Text("One step per line", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = r.steps.joinToString("\n"),
                        onValueChange = { v -> vm.edit { it.copy(steps = v.split('\n')) } },
                        label = { Text("Method") },
                        modifier = Modifier.fillMaxWidth().height(320.dp),
                    )
                }
                else -> {
                    Text(r.name.ifBlank { "(unnamed)" }, style = MaterialTheme.typography.titleLarge)
                    Text("${r.mealType.label} · ${r.cuisine} · ${r.servings} servings · ${r.prepMinutes} min")
                    r.ingredients.forEach { Text("• ${it.grams.fmt()} g ${it.food}") }
                    r.steps.filter { it.isNotBlank() }.forEachIndexed { i, s -> Text("${i + 1}. $s") }
                    message?.let { Text(it, color = Danger) }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { if (step == 0) onDone() else vm.goTo(step - 1) }, modifier = Modifier.weight(1f)) {
                Text(if (step == 0) "Cancel" else "Back")
            }
            if (step < RecipeEditorViewModel.STEPS.lastIndex) {
                Button(onClick = { vm.goTo(step + 1) }, modifier = Modifier.weight(1f)) { Text("Next") }
            } else {
                Button(onClick = { vm.save(onDone) }, modifier = Modifier.weight(1f)) { Text("Save") }
            }
        }
    }

    swapIndex?.let { index ->
        AlertDialog(
            onDismissRequest = { swapIndex = null },
            title = { Text("Swap ${r.ingredients.getOrNull(index)?.food ?: "ingredient"}") },
            text = {
                FoodSearch(foods, onPick = {
                    vm.swapIngredient(index, it)
                    swapIndex = null
                })
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { swapIndex = null }) { Text("Cancel") } },
        )
    }
}
