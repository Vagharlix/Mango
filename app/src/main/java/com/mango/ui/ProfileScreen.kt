package com.mango.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mango.data.ActivityLevel
import com.mango.data.Equipment
import com.mango.data.Goal
import com.mango.data.ProgramMode
import com.mango.data.Repository
import com.mango.data.Sex
import com.mango.data.UserProfile
import com.mango.domain.NutritionEngine
import com.mango.domain.Periodization
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

class ProfileViewModel(repo: Repository) : MangoViewModel(repo) {
    private val _draft = MutableStateFlow(UserProfile())
    val draft = _draft.asStateFlow()
    private val _isNew = MutableStateFlow(true)
    val isNew = _isNew.asStateFlow()

    init {
        viewModelScope.launch {
            repo.profile.first()?.let {
                _draft.value = it
                _isNew.value = false
            }
            // Equipment is edited live from several screens; keep the draft in sync with what is stored.
            repo.profile.collect { stored -> if (stored != null) _draft.update { it.copy(equipment = stored.equipment) } }
        }
    }

    /** Equipment changes apply immediately (no need to press Save) once a profile exists. */
    fun toggleEquipment(e: Equipment) {
        val current = _draft.value.equipment
        val next = if (e in current) current - e else current + e
        update { it.copy(equipment = next) }
        if (!_isNew.value) launchSafe { repo.updateEquipment(next) }
    }

    fun update(transform: (UserProfile) -> UserProfile) {
        _draft.value = transform(_draft.value)
    }

    fun save() = launchSafe {
        val p = _draft.value
        require(p.age in 14..100) { "Age must be between 14 and 100" }
        require(p.heightCm in 120.0..230.0) { "Height must be 120-230 cm" }
        require(p.weightKg in 30.0..200.0) { "Weight must be 30-200 kg" }
        repo.saveProfile(p)
        _draft.value = repo.profile.first() ?: p
        _isNew.value = false
        toast("Profile saved – training and meals updated")
    }

    fun restartProgram() {
        update { it.copy(programStartEpochDay = repo.today()) }
        save()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileScreen() {
    val vm = mangoViewModel { ProfileViewModel(it) }
    val p by vm.draft.collectAsStateWithLifecycle()
    val isNew by vm.isNew.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(if (isNew) "Welcome to Mango" else "Profile", style = MaterialTheme.typography.headlineSmall)
            if (isNew) {
                Text(
                    "Set up your biometrics to generate your training block and meal plan. Everything is computed on this device.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionCard {
                Text("Biometrics", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Age", p.age.toDouble(), { v -> vm.update { it.copy(age = v.toInt()) } }, Modifier.weight(1f))
                    NumberField("Height", p.heightCm, { v -> vm.update { it.copy(heightCm = v) } }, Modifier.weight(1f), suffix = "cm")
                    NumberField("Weight", p.weightKg, { v -> vm.update { it.copy(weightKg = v) } }, Modifier.weight(1f), suffix = "kg")
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Sex.entries.forEach { s ->
                        FilterChip(
                            selected = p.sex == s,
                            onClick = { vm.update { it.copy(sex = s) } },
                            label = { Text(s.name.lowercase().replaceFirstChar { it.uppercase() }) },
                        )
                    }
                }
                Dropdown("Activity level", p.activityLevel.label, ActivityLevel.entries.map { it to it.label }) { a ->
                    vm.update { it.copy(activityLevel = a) }
                }
            }

            SectionCard {
                Text("Strength (1RM)", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Enter the added load at your max. Use a negative number if you need assistance. Loads are prescribed as % of total system load (bodyweight + added).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("20 mm edge", p.fingerMaxAddedKg, { v -> vm.update { it.copy(fingerMaxAddedKg = v) } }, Modifier.weight(1f), allowNegative = true, suffix = "kg")
                    NumberField("Pull-up", p.pullUpMaxAddedKg, { v -> vm.update { it.copy(pullUpMaxAddedKg = v) } }, Modifier.weight(1f), allowNegative = true, suffix = "kg")
                }
            }

            SectionCard {
                Text("Training program", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Goal.entries.forEach { g ->
                        FilterChip(selected = p.goal == g, onClick = { vm.update { it.copy(goal = g) } }, label = { Text(g.label) })
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = p.programMode == ProgramMode.EVERGREEN,
                        onClick = { vm.update { it.copy(programMode = ProgramMode.EVERGREEN) } },
                        label = { Text("Evergreen (16-wk cycle)") },
                    )
                    FilterChip(
                        selected = p.programMode == ProgramMode.FIXED,
                        onClick = { vm.update { it.copy(programMode = ProgramMode.FIXED) } },
                        label = { Text("Fixed length") },
                    )
                }
                if (p.programMode == ProgramMode.FIXED) {
                    NumberField("Program length", p.programWeeks.toDouble(), { v -> vm.update { it.copy(programWeeks = v.toInt()) } }, suffix = "weeks")
                    val split = Periodization.allocateWeeks(p.programWeeks)
                    Text(
                        split.entries.joinToString(" · ") { "${it.key.label}: ${it.value} wk" },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!isNew) {
                    TextButton(onClick = vm::restartProgram) { Text("Restart program from today") }
                }
            }

            SectionCard {
                Text("Training equipment", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Select what you can use right now – change it any time (gym, home, travelling). Missing equipment is swapped for the closest evidence-based alternative.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                EquipmentPicker(p.equipment, vm::toggleEquipment)
            }

            SectionCard {
                Text("Grocery day", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    DayOfWeek.entries.forEach { d ->
                        FilterChip(
                            selected = p.groceryDay == d.value,
                            onClick = { vm.update { it.copy(groceryDay = d.value) } },
                            label = { Text(d.getDisplayName(TextStyle.SHORT, Locale.getDefault())) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Southern hemisphere seasons", Modifier.weight(1f))
                    Switch(checked = p.southernHemisphere, onCheckedChange = { v -> vm.update { it.copy(southernHemisphere = v) } })
                }
            }

            SectionCard {
                Text("Disliked ingredients & allergies", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Recipes containing these words or allergen groups (e.g. pork, nuts, dairy, gluten, shellfish) are never planned.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                var entry by remember { mutableStateOf("") }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(entry, { entry = it }, label = { Text("e.g. mushrooms") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = {
                        val t = entry.trim().lowercase()
                        if (t.isNotEmpty()) vm.update { it.copy(dislikedIngredients = (it.dislikedIngredients + t).distinct()) }
                        entry = ""
                    }) { Text("Add") }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    p.dislikedIngredients.forEach { d ->
                        InputChip(
                            selected = false,
                            onClick = { vm.update { it.copy(dislikedIngredients = it.dislikedIngredients - d) } },
                            label = { Text(d) },
                            trailingIcon = { Icon(Icons.Filled.Close, contentDescription = "Remove $d") },
                        )
                    }
                }
            }

            val today = remember { java.time.LocalDate.now().toEpochDay() }
            val phase = if (isNew) com.mango.domain.Phase.BASE else Periodization.phaseOn(p, today)
            val t = NutritionEngine.targets(p, phase)
            SectionCard {
                Text("Daily targets · ${phase.label}", style = MaterialTheme.typography.titleMedium)
                Text("BMR (Mifflin-St Jeor): ${NutritionEngine.bmr(p).fmt()} kcal · TDEE: ${t.kcal.fmt()} kcal")
                Text("Protein ${t.proteinG.fmt()} g (${t.proteinPerKg.fmt(1)} g/kg) · Carbs ${t.carbsG.fmt()} g · Fat ${t.fatG.fmt()} g")
                Text(
                    "Fibre ≥ ${t.fiberG.fmt()} g · Vitamin C ≥ ${t.vitaminCMg.fmt()} mg · Omega-3 (ALA) ≥ ${t.omega3G.fmt(1)} g",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Tendon health: protein is spread across all meals from whole foods (meat, fish, eggs, dairy, tofu, legumes) rich in glycine and proline, paired with vitamin C from seasonal produce. No supplements needed.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Button(onClick = vm::save, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(if (isNew) "Create profile" else "Save changes")
            }
            Spacer(Modifier.height(24.dp))
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EquipmentPicker(selected: List<Equipment>, onToggle: (Equipment) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Equipment.entries.forEach { e ->
            FilterChip(selected = e in selected, onClick = { onToggle(e) }, label = { Text(e.label) })
        }
    }
}

@Composable
fun <T> Dropdown(label: String, current: String, options: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(current)
            }
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = {
                    onSelect(value)
                    open = false
                })
            }
        }
    }
}
