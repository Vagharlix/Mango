package com.mango.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mango.data.MealType
import com.mango.data.Recipe
import com.mango.data.Repository
import com.mango.data.UserProfile
import com.mango.domain.FoodPreferences
import com.mango.domain.MealPlanner
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class FoodPrefsDraft(
    val carbOff: Set<String> = emptySet(),
    val proteinOff: Set<String> = emptySet(),
    val breakfastPicks: Set<Long> = emptySet(),
    val breakfastVariety: Int = 2,
)

class FoodPreferencesViewModel(repo: Repository) : MangoViewModel(repo) {
    /** Breakfasts the user can pick from (allowed by their dislikes). */
    val breakfasts = combine(repo.recipes, repo.foods, repo.profile) { recipes, foods, p ->
        val byName = foods.associateBy { it.name }
        recipes.filter {
            it.mealType == MealType.BREAKFAST && !it.isHidden && (p == null || MealPlanner.isAllowed(it, p.dislikedIngredients, byName))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    var draft by mutableStateOf(FoodPrefsDraft())
        private set

    init {
        viewModelScope.launch {
            repo.profile.first()?.let { p: UserProfile ->
                draft = FoodPrefsDraft(p.carbOff.toSet(), p.proteinOff.toSet(), p.breakfastPicks.toSet(), p.breakfastVariety)
            }
        }
    }

    fun edit(transform: (FoodPrefsDraft) -> FoodPrefsDraft) {
        draft = transform(draft)
    }

    fun save(onDone: () -> Unit) = launchSafe {
        val d = draft
        require(FoodPreferences.SWAPPABLE_CARBS.any { it !in d.carbOff }) { "Keep at least one of rice, pasta, potatoes, quinoa, noodles, barley or buckwheat" }
        require(d.breakfastPicks.isEmpty() || d.breakfastPicks.size >= 2) { "Pick at least 2 breakfasts so they can alternate" }
        repo.updateFoodPreferences(d.carbOff.toList(), d.proteinOff.toList(), d.breakfastPicks.toList(), d.breakfastVariety)
        toast("Preferences saved – this week's menu was updated")
        onDone()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FoodPreferencesScreen(onBack: () -> Unit) {
    val vm = mangoViewModel { FoodPreferencesViewModel(it) }
    val breakfasts by vm.breakfasts.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val d = vm.draft

    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { BackHeader("Food preferences", onBack) }

            item { SectionLabel("Carbs", Modifier.padding(top = 4.dp)) }
            item {
                Text(
                    "Untick what you don't want to eat. Recipes switch to an allowed carb at equal energy (e.g. quinoa → rice). " +
                        "Bread, wraps and oats shape the dish, so recipes with them are skipped instead.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FoodPreferences.CARB_TYPES.forEach { c ->
                        FilterChip(
                            selected = c !in d.carbOff,
                            onClick = { vm.edit { it.copy(carbOff = if (c in it.carbOff) it.carbOff - c else it.carbOff + c) } },
                            label = { Text(c.replaceFirstChar { it.uppercase() }) },
                        )
                    }
                }
            }

            item { SectionLabel("Meat & fish", Modifier.padding(top = 8.dp)) }
            item {
                Text(
                    "Change it whenever you like, e.g. no pork this week. Recipes swap to a similar cut (pork → chicken, beef mince → turkey mince, salmon → mackerel); " +
                        "if nothing similar is allowed, the recipe is skipped. Eggs, dairy, tofu and legumes are always available.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FoodPreferences.PROTEIN_TYPES.forEach { pt ->
                        FilterChip(
                            selected = pt !in d.proteinOff,
                            onClick = { vm.edit { it.copy(proteinOff = if (pt in it.proteinOff) it.proteinOff - pt else it.proteinOff + pt) } },
                            label = { Text(pt.replaceFirstChar { it.uppercase() }) },
                        )
                    }
                }
            }

            item { SectionLabel("Breakfasts", Modifier.padding(top = 8.dp)) }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Different breakfasts per week")
                        Text("They alternate, so you never have the same one two days in a row.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { vm.edit { it.copy(breakfastVariety = (it.breakfastVariety - 1).coerceAtLeast(2)) } }) {
                        Icon(Icons.Filled.Remove, contentDescription = "Fewer breakfasts")
                    }
                    Text(d.breakfastVariety.toString(), style = MaterialTheme.typography.titleMedium)
                    IconButton(onClick = { vm.edit { it.copy(breakfastVariety = (it.breakfastVariety + 1).coerceAtMost(7)) } }) {
                        Icon(Icons.Filled.Add, contentDescription = "More breakfasts")
                    }
                }
            }
            item {
                Text(
                    if (d.breakfastPicks.isEmpty()) "Pick the breakfasts you like – none picked means any breakfast."
                    else "${d.breakfastPicks.size} picked – the week rotates between them.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                GroupedCard {
                    breakfasts.forEachIndexed { i, r ->
                        if (i > 0) HorizontalDivider(color = Hairline)
                        BreakfastRow(r, r.id in d.breakfastPicks) {
                            vm.edit { it.copy(breakfastPicks = if (r.id in it.breakfastPicks) it.breakfastPicks - r.id else it.breakfastPicks + r.id) }
                        }
                    }
                }
            }
        }
        BottomActionBar {
            message?.let { Text(it, fontSize = 13.sp, color = Danger) }
            PrimaryButton("Save & update menu", { vm.save(onBack) }, icon = null)
        }
    }
}

@Composable
private fun BreakfastRow(r: Recipe, picked: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = picked, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f)) {
            Text(r.name, fontSize = 15.sp)
            Text("${r.prepMinutes} min · ${r.perServing.kcal.fmt()} kcal · P ${r.perServing.protein.fmt()} g", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
