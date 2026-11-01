package com.mango.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.mango.MangoApp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mango.data.ActivityLevel
import com.mango.data.Equipment
import com.mango.data.Goal
import com.mango.data.ProgramMode
import com.mango.data.Repository
import com.mango.data.Sex
import com.mango.data.UserProfile
import com.mango.domain.DislikeSuggestions
import com.mango.domain.NutritionEngine
import com.mango.domain.Periodization
import com.mango.domain.Phase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/** Draft of the profile shared by the Options sub-pages (body, training, dislikes); saved explicitly. */
class ProfileViewModel(repo: Repository) : MangoViewModel(repo) {
    private val _draft = MutableStateFlow(UserProfile())
    val draft = _draft.asStateFlow()
    val foods = repo.foods.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _isNew = MutableStateFlow(true)
    val isNew = _isNew.asStateFlow()

    init {
        viewModelScope.launch {
            repo.profile.first()?.let {
                _draft.value = it
                _isNew.value = false
            }
            // Equipment is edited live from several screens; keep the draft in sync with what is stored.
            // A profile appearing from elsewhere (e.g. a restored backup) replaces the blank welcome draft.
            repo.profile.collect { stored ->
                if (stored == null) return@collect
                if (_isNew.value) {
                    _draft.value = stored
                    _isNew.value = false
                } else {
                    _draft.update { it.copy(equipment = stored.equipment) }
                }
            }
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

    fun save(onDone: () -> Unit = {}) = launchSafe {
        val p = _draft.value
        require(p.age in 14..100) { "Age must be between 14 and 100" }
        require(p.heightCm in 120.0..230.0) { "Height must be 120-230 cm" }
        require(p.weightKg in 30.0..200.0) { "Weight must be 30-200 kg" }
        val created = _isNew.value
        repo.saveProfile(p)
        _draft.value = repo.profile.first() ?: p
        _isNew.value = false
        toast(if (created) "Profile created – your plan is ready" else "Saved – training and meals updated")
        onDone()
    }

    fun restartProgram() {
        update { it.copy(programStartEpochDay = repo.today()) }
        save()
    }
}

data class OptionsState(val profile: UserProfile? = null, val staplesKept: Int = 0)

/** Options home: summaries plus the settings that are a single tap (grocery day, seasons). */
class OptionsViewModel(repo: Repository) : MangoViewModel(repo) {
    val state = combine(repo.profile, repo.foods) { p, foods ->
        OptionsState(p, if (p == null) 0 else repo.keptStaples(foods, p).size)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OptionsState())

    fun quickSave(transform: (UserProfile) -> UserProfile) = launchSafe {
        val p = state.value.profile ?: return@launchSafe
        repo.saveProfile(transform(p))
    }
}

// ============================ Options home ============================

@Composable
fun OptionsScreen(
    onOpenBody: () -> Unit,
    onOpenTraining: () -> Unit,
    onOpenFoodPrefs: () -> Unit,
    onOpenDislikes: () -> Unit,
    onOpenStaples: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenBackup: () -> Unit,
) {
    val vm = mangoViewModel { OptionsViewModel(it) }
    val backup by (LocalContext.current.applicationContext as MangoApp).backups.status.collectAsStateWithLifecycle()
    val s by vm.state.collectAsStateWithLifecycle()
    val p = s.profile
    var pickDay by remember { mutableStateOf(false) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { ScreenHeader("Options", eyebrow = "Your settings") }
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                    .clickable(onClick = onOpenBody)
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Person, contentDescription = null, tint = MangoOrange)
                }
                Column(Modifier.weight(1f)) {
                    Text(if (p == null) "Create your profile" else "Body & daily targets", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (p == null) "Age, height, weight – everything else is computed on this device"
                        else "${p.weightKg.fmt(1)} kg · ${p.heightCm.fmt()} cm · ${p.age} y · ${NutritionEngine.tdee(p).fmt()} kcal / day",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Faint)
            }
        }
        if (p != null) {
            item {
                OptionsSection("Training") {
                    OptionRow("Training plan", (if (p.programMode == ProgramMode.EVERGREEN) "Evergreen 16-week cycle" else "${p.programWeeks}-week program") + " · ${p.goal.label}", MangoOrange, first = true, onClick = onOpenTraining)
                    OptionRow("Equipment", "${p.equipment.size} items · change any time", MangoOrange, onClick = onOpenTraining)
                    OptionRow("Strength baseline", "20 mm edge ${signedKg(p.fingerMaxAddedKg)} · pull-up ${signedKg(p.pullUpMaxAddedKg)}", MangoOrange, onClick = onOpenTraining)
                }
            }
            item {
                OptionsSection("Food") {
                    val prefs = buildList {
                        add("${p.breakfastVariety} breakfasts")
                        if (p.carbOff.isNotEmpty()) add("no " + p.carbOff.joinToString("/"))
                        if (p.proteinOff.isNotEmpty()) add("no " + p.proteinOff.joinToString("/"))
                    }.joinToString(" · ")
                    OptionRow("Food preferences", prefs, Leaf, first = true, onClick = onOpenFoodPrefs)
                    OptionRow("Dislikes & allergies", p.dislikedIngredients.joinToString(", ").ifEmpty { "None" }, Leaf, onClick = onOpenDislikes)
                    OptionRow("Grocery day", DayOfWeek.of(p.groceryDay).getDisplayName(TextStyle.FULL, Locale.getDefault()), Leaf) { pickDay = true }
                    OptionRow("Spice rack", "${s.staplesKept} staples kept stocked", Leaf, onClick = onOpenStaples)
                }
            }
            item {
                OptionsSection("App") {
                    Row(Modifier.fillMaxWidth().padding(14.dp, 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(Sky, CircleShape))
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text("Southern hemisphere", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text("Which produce counts as in season", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = p.southernHemisphere, onCheckedChange = { v -> vm.quickSave { it.copy(southernHemisphere = v) } })
                    }
                    OptionRow(
                        "Backup & restore",
                        when {
                            backup.error != null -> "Auto-save needs attention"
                            backup.autoFile != null -> "Auto-save on" + if (backup.lastSaved.isNotEmpty()) " · ${backup.lastSaved}" else ""
                            else -> "Save your settings and recipes to a file"
                        },
                        if (backup.error != null) Danger else Sky,
                        onClick = onOpenBackup,
                    )
                    OptionRow("About & sources", "Science references · version 0.8 beta", Sky, onClick = onOpenAbout)
                }
            }
        }
        if (p == null) {
            item {
                OptionsSection("Already used Mango?") {
                    OptionRow("Restore a backup", "Bring back your profile, recipes and plan from a backup file", Sky, first = true, onClick = onOpenBackup)
                }
            }
        }
    }

    if (pickDay && p != null) {
        AlertDialog(
            onDismissRequest = { pickDay = false },
            title = { Text("Grocery day") },
            text = {
                Column {
                    Text("The menu review and shopping list are built around this day.", style = MaterialTheme.typography.bodySmall)
                    DayOfWeek.entries.forEach { d ->
                        Text(
                            d.getDisplayName(TextStyle.FULL, Locale.getDefault()),
                            fontSize = 16.sp,
                            fontWeight = if (d.value == p.groceryDay) FontWeight.Bold else FontWeight.Normal,
                            color = if (d.value == p.groceryDay) MangoOrange else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth().clickable {
                                vm.quickSave { it.copy(groceryDay = d.value) }
                                pickDay = false
                            }.padding(vertical = 10.dp),
                        )
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pickDay = false }) { Text("Cancel") } },
        )
    }
}

private fun signedKg(v: Double) = (if (v >= 0) "+" else "") + v.fmt(1).removeSuffix(".0") + " kg"

@Composable
private fun OptionsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(title, Modifier.padding(start = 4.dp))
        GroupedCard(content)
    }
}

@Composable
private fun OptionRow(label: String, value: String, dot: Color, first: Boolean = false, onClick: () -> Unit) {
    if (!first) HorizontalDivider(color = Hairline)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(14.dp, 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(dot, CircleShape))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(value, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Faint)
    }
}

/** Shared frame for the Options sub-pages: back header, scrolling content, snackbar, pinned save button. */
@Composable
private fun SettingsPage(
    title: String,
    vm: ProfileViewModel,
    onBack: () -> Unit,
    saveLabel: String = "Save",
    onSave: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp, 4.dp, 16.dp, 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                BackHeader(title, onBack)
                content()
            }
            BottomActionBar { PrimaryButton(saveLabel, onSave, icon = null) }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 90.dp))
    }
}

// ============================ Body & targets ============================

@Composable
fun BodyScreen(onBack: () -> Unit, onRestore: () -> Unit = {}) {
    val vm = mangoViewModel { ProfileViewModel(it) }
    val p by vm.draft.collectAsStateWithLifecycle()
    val isNew by vm.isNew.collectAsStateWithLifecycle()
    SettingsPage(
        title = if (isNew) "Welcome to Mango" else "Body & targets",
        vm = vm,
        onBack = onBack,
        saveLabel = if (isNew) "Create profile" else "Save",
        onSave = { vm.save(onDone = if (isNew) onBack else ({})) },
    ) {
        if (isNew) {
            Text(
                "Your training block and meal plan are generated from these numbers, entirely on this device. " +
                    "Fine-tune training, equipment and food afterwards in Options.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onRestore) { Text("Used Mango before? Restore a backup instead") }
        }
        SectionLabel("Biometrics")
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
        Dropdown("Activity level", p.activityLevel.label, ActivityLevel.entries.map { it to it.label }) { a -> vm.update { it.copy(activityLevel = a) } }

        val today = remember { java.time.LocalDate.now().toEpochDay() }
        val phase = if (isNew) Phase.BASE else Periodization.phaseOn(p, today)
        val t = NutritionEngine.targets(p, phase)
        SectionLabel("Daily targets · ${phase.label}", Modifier.padding(top = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Energy", "${t.kcal.fmt()} kcal", Modifier.weight(1f), highlight = true)
            StatTile("Protein", "${t.proteinG.fmt()} g", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Carbs", "${t.carbsG.fmt()} g", Modifier.weight(1f))
            StatTile("Fat", "${t.fatG.fmt()} g", Modifier.weight(1f))
        }
        Text(
            "BMR (Mifflin-St Jeor) ${NutritionEngine.bmr(p).fmt()} kcal × activity ${p.activityLevel.multiplier}. Protein ${t.proteinPerKg.fmt(1)} g/kg for this phase. " +
                "Fibre ≥ ${t.fiberG.fmt()} g · vitamin C ≥ ${t.vitaminCMg.fmt()} mg · omega-3 ≥ ${t.omega3G.fmt(1)} g.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "Tendon health: protein spread across meals from whole foods rich in glycine and proline, with vitamin C from seasonal produce. No supplements needed.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ============================ Training ============================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TrainingSettingsScreen(onBack: () -> Unit) {
    val vm = mangoViewModel { ProfileViewModel(it) }
    val p by vm.draft.collectAsStateWithLifecycle()
    SettingsPage(title = "Training", vm = vm, onBack = onBack, onSave = { vm.save(onBack) }) {
        SectionLabel("Goal")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Goal.entries.forEach { g -> FilterChip(selected = p.goal == g, onClick = { vm.update { it.copy(goal = g) } }, label = { Text(g.label) }) }
        }

        SectionLabel("Program", Modifier.padding(top = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = p.programMode == ProgramMode.EVERGREEN, onClick = { vm.update { it.copy(programMode = ProgramMode.EVERGREEN) } }, label = { Text("Evergreen") })
            FilterChip(selected = p.programMode == ProgramMode.FIXED, onClick = { vm.update { it.copy(programMode = ProgramMode.FIXED) } }, label = { Text("Fixed length") })
        }
        if (p.programMode == ProgramMode.FIXED) {
            NumberField("Program length", p.programWeeks.toDouble(), { v -> vm.update { it.copy(programWeeks = v.toInt()) } }, suffix = "weeks")
            Text(
                Periodization.allocateWeeks(p.programWeeks).entries.joinToString(" · ") { "${it.key.label}: ${it.value} wk" },
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text("Endless 16-week cycle: Base → Max Strength → Power → Peak.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = vm::restartProgram) { Text("Restart program from today") }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            SectionLabel("Equipment", Modifier.weight(1f))
            Text("Saved instantly", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        EquipmentPicker(p.equipment, vm::toggleEquipment)
        Text("Missing equipment is replaced by the closest evidence-based alternative.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

        SectionLabel("Strength baseline (1RM, added kg)", Modifier.padding(top = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("20 mm edge", p.fingerMaxAddedKg, { v -> vm.update { it.copy(fingerMaxAddedKg = v) } }, Modifier.weight(1f), allowNegative = true, suffix = "kg")
            NumberField("Pull-up", p.pullUpMaxAddedKg, { v -> vm.update { it.copy(pullUpMaxAddedKg = v) } }, Modifier.weight(1f), allowNegative = true, suffix = "kg")
        }
        Text(
            "Negative = assisted. Loads are a % of total system load (bodyweight + added). Each block's benchmark day updates the next block automatically.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ============================ Dislikes & allergies ============================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DislikesScreen(onBack: () -> Unit) {
    val vm = mangoViewModel { ProfileViewModel(it) }
    val p by vm.draft.collectAsStateWithLifecycle()
    val foods by vm.foods.collectAsStateWithLifecycle()
    var entry by remember { mutableStateOf("") }
    fun addDislike(term: String) {
        val t = term.trim().lowercase()
        if (t.isNotEmpty()) vm.update { it.copy(dislikedIngredients = (it.dislikedIngredients + t).distinct()) }
        entry = ""
    }
    SettingsPage(title = "Dislikes & allergies", vm = vm, onBack = onBack, saveLabel = "Save & update menu", onSave = { vm.save(onBack) }) {
        Text(
            "Recipes containing these foods or allergen groups (pork, nuts, dairy, gluten, shellfish…) are never planned, and never used as replacements.",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(entry, { entry = it }, label = { Text("Search a food or allergen") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { addDislike(entry) }) { Text("Add") }
        }
        val suggestions = remember(entry, foods, p.dislikedIngredients) { DislikeSuggestions.suggest(entry, foods, p.dislikedIngredients) }
        if (suggestions.isNotEmpty()) {
            Text(if (entry.isBlank()) "Common allergens" else "Suggestions", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                suggestions.forEach { sug ->
                    AssistChip(
                        onClick = { addDislike(sug.term) },
                        label = { Text(if (sug.isGroup && sug.foodCount > 1) "${sug.term} · ${sug.foodCount} foods" else sug.term) },
                        leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp)) },
                    )
                }
            }
        }
        if (p.dislikedIngredients.isNotEmpty()) {
            SectionLabel("Excluded", Modifier.padding(top = 4.dp))
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
    }
}

// ============================ About & sources ============================

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val sources = listOf(
        "Energy" to "Mifflin MD, St Jeor ST et al. (1990) Am J Clin Nutr 51:241 – BMR equation; activity factors from McArdle, Katch & Katch.",
        "Protein" to "Jäger R et al. (2017) ISSN Position Stand, JISSN 14:20; Morton RW et al. (2018) Br J Sports Med 52:376 – 1.6-2.2 g/kg.",
        "Carbs & fat" to "Thomas DT, Erdman KA, Burke LM (2016) ACSM/AND/DC Joint Position, Med Sci Sports Exerc 48:543.",
        "Micronutrients" to "Institute of Medicine Dietary Reference Intakes – fibre, vitamin C, ALA omega-3.",
        "Food data" to "USDA FoodData Central (SR Legacy / Foundation), per 100 g.",
        "Leftovers" to "USDA Food Safety: cooked leftovers keep 3-4 days refrigerated.",
        "Deload & taper" to "Pritchard et al. (2015) Strength Cond J 37:72; Bosquet et al. (2007) Med Sci Sports Exerc 39:1358; Magnusson, Langberg & Kjaer (2010) Nat Rev Rheumatol 6:262.",
        "Finger training" to "López-Rivera & González-Badillo (2012) Sports Technology 5:100; Medernach et al. (2015) J Strength Cond Res 29:3093; Lattice Training; Hörst, Training for Climbing (2016).",
        "Strength & mobility" to "ACSM Progression Models in Resistance Training (2009); Vagy, Climb Injury-Free (2016); Kurz, Stretching Scientifically (2003).",
    )
    LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { BackHeader("About & sources", onBack) }
        item {
            Text(
                "Mango 0.8 beta · works fully offline · no account, no AI service, no tracking. Every calculation runs on this device.",
                fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            GroupedCard {
                sources.forEachIndexed { i, (title, text) ->
                    if (i > 0) HorizontalDivider(color = Hairline)
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(text, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ============================ Shared pickers ============================

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
