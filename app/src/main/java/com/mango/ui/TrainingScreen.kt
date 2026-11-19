package com.mango.ui

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.KeyboardType
import kotlin.math.abs
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mango.data.Equipment
import com.mango.data.ProgramMode
import com.mango.data.Repository
import com.mango.data.UserProfile
import com.mango.domain.MaxTest
import com.mango.domain.MaxTests
import com.mango.domain.Periodization
import com.mango.domain.Phase
import com.mango.domain.Prescription
import com.mango.domain.StrengthBaseline
import com.mango.domain.TrainingDay
import com.mango.domain.TrainingWeek
import com.mango.domain.formatDuration
import com.mango.timer.WorkoutTimerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

data class TrainingState(
    val loading: Boolean = true,
    val profile: UserProfile? = null,
    val weeks: List<TrainingWeek> = emptyList(),
    val currentWeek: Int = 0,
    val today: Long = 0,
    val baseline: StrengthBaseline? = null,
    /** Latest result of each tested max, with the one before it (progress). */
    val maxes: List<MaxRow> = emptyList(),
) {
    fun day(week: Int, day: Int): TrainingDay? = weeks.getOrNull(week)?.days?.getOrNull(day)
}

data class MaxRow(val test: MaxTest, val value: Double, val day: Long, val previous: Double?)

/** Shared by the week, session, exercise and plan screens; the schedule is derived on the fly. */
class TrainingViewModel(repo: Repository) : MangoViewModel(repo) {
    val state = combine(repo.profile, repo.exercises, repo.benchmarks) { p, exercises, benchmarks ->
        val today = repo.today()
        if (p == null || exercises.isEmpty()) {
            TrainingState(loading = p != null, profile = p, today = today)
        } else {
            val current = Periodization.weekIndexOn(p, today)
            val maxes = MaxTest.entries.mapNotNull { t ->
                val history = benchmarks.filter { t.key in it.results }.sortedBy { it.epochDay }
                history.lastOrNull()?.let { MaxRow(t, it.results.getValue(t.key), it.epochDay, history.dropLast(1).lastOrNull()?.results?.get(t.key)) }
            }
            TrainingState(
                loading = false,
                profile = p,
                weeks = Periodization.build(p, exercises.associateBy { it.id }, benchmarks, today),
                currentWeek = current,
                today = today,
                baseline = MaxTests.baselineOn(p, benchmarks, today + 1),
                maxes = maxes,
            )
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrainingState())

    fun toggleEquipment(e: Equipment) = launchSafe {
        val current = state.value.profile?.equipment ?: return@launchSafe
        repo.updateEquipment(if (e in current) current - e else current + e)
    }

    fun saveResults(results: Map<String, Double>) = launchSafe {
        repo.addBenchmark(results)
        toast("Results saved – loads, reps and holds adapt from tomorrow")
    }

    fun startExercise(context: Context, p: Prescription) {
        WorkoutTimerService.start(context, p.exercise.name, Periodization.intervals(p))
    }

    fun startSession(context: Context, day: TrainingDay) {
        WorkoutTimerService.start(context, day.title, Periodization.sessionIntervals(day.items))
    }
}

// ============================ Week (Train tab) ============================

@Composable
fun TrainingScreen(
    onOpenSession: (week: Int, day: Int) -> Unit,
    onOpenPlan: () -> Unit,
    onSetupProfile: () -> Unit,
    onOpenTrainingSettings: () -> Unit,
) {
    val vm = mangoViewModel { TrainingViewModel(it) }
    val s by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var editEquipment by remember { mutableStateOf(false) }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }
    val profile = s.profile
    when {
        s.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        profile == null -> EmptyProfile(onSetupProfile)
        else -> Box(Modifier.fillMaxSize()) {
            val shownWeek = s.currentWeek.coerceIn(0, (s.weeks.size - 1).coerceAtLeast(0))
            val week = s.weeks.getOrNull(shownWeek)
            val todayIndex = week?.days?.indexOfFirst { it.epochDay == s.today } ?: -1
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    ScreenHeader("Train", eyebrow = s.today.asDate("EEEE d MMM")) {
                        RoundIconButton(Icons.Filled.FitnessCenter, "Equipment", onClick = { editEquipment = true })
                    }
                }
                item { PlanStrip(s, onOpenPlan) }
                if (s.maxes.isNotEmpty()) item { MaxesCard(s.maxes) }
                item {
                    val today = week?.days?.getOrNull(todayIndex)
                    when {
                        s.currentWeek < 0 -> HeroCard("COMING UP", "Program starts ${profile.programStartEpochDay.asDate()}", "Your first week is listed below.")
                        s.currentWeek >= s.weeks.size -> HeroCard("COMPLETE", "Program finished", "Restart it or switch to a new program.") {
                            PrimaryButton("Training settings", onOpenTrainingSettings, icon = null)
                        }
                        today == null || today.isRest -> HeroCard("TODAY", "Rest day", "Sleep, eat well and let your tendons remodel.", labelColor = Leaf)
                        else -> HeroCard(
                            label = if (today.isBenchmark) "TODAY · TEST DAY" else "TODAY",
                            title = today.title,
                            subtitle = sessionMeta(today),
                            labelColor = if (today.isBenchmark) Warn else MangoOrange,
                        ) { PrimaryButton("Start session", { onOpenSession(shownWeek, todayIndex) }) }
                    }
                }
                if (week != null) {
                    item { SectionLabel("This week") }
                    items(week.days.filterIndexed { i, _ -> i != todayIndex }, key = { it.epochDay }) { day ->
                        DayRow(day) { onOpenSession(shownWeek, day.dayNumber - 1) }
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
    if (editEquipment) EquipmentDialog(s.profile?.equipment.orEmpty(), vm::toggleEquipment) { editEquipment = false }
}

fun sessionMeta(day: TrainingDay): String =
    "${day.items.size} exercise${if (day.items.size == 1) "" else "s"} · ≈ ${Periodization.estimateMinutes(day.items)} min"

/** Compact long-term overview: one segment per week of the cycle, current week raised. */
@Composable
private fun PlanStrip(s: TrainingState, onOpenPlan: () -> Unit) {
    val profile = s.profile ?: return
    val evergreen = profile.programMode == ProgramMode.EVERGREEN
    val cycleStart = if (evergreen) (s.currentWeek.coerceAtLeast(0) / Periodization.EVERGREEN_WEEKS) * Periodization.EVERGREEN_WEEKS else 0
    val cycle = s.weeks.drop(cycleStart).take(if (evergreen) Periodization.EVERGREEN_WEEKS else s.weeks.size)
    val current = s.weeks.getOrNull(s.currentWeek)
    val nextTest = s.weeks.drop(s.currentWeek.coerceAtLeast(0)).flatMap { it.days }
        .firstOrNull { it.isBenchmark && it.epochDay >= s.today }
    SectionCard(Modifier.clickable(onClick = onOpenPlan)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (current != null) "Week ${current.index + 1 - cycleStart} of ${cycle.size} · " else "Your plan",
                fontSize = 15.sp, fontWeight = FontWeight.Bold,
            )
            if (current != null) {
                Text(current.phase.label, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = phaseColor(current.phase), modifier = Modifier.weight(1f), maxLines = 1)
            } else {
                Box(Modifier.weight(1f))
            }
            Text("Full plan ›", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MangoOrange)
        }
        Row(Modifier.fillMaxWidth().height(20.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            cycle.forEach { w ->
                val isCurrent = w.index == s.currentWeek
                Box(
                    Modifier
                        .weight(1f)
                        .height(if (isCurrent) 18.dp else if (w.isDeload) 6.dp else 10.dp)
                        .then(if (isCurrent) Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, RoundedCornerShape(3.dp)) else Modifier)
                        .background(phaseColor(w.phase).copy(alpha = if (w.index < s.currentWeek) 0.45f else 0.9f), RoundedCornerShape(3.dp)),
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            cycle.map { it.phase }.distinct().forEach { phase ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.size(8.dp).background(phaseColor(phase), RoundedCornerShape(2.dp)))
                    Text(phase.label.substringBefore(" /").substringBefore(" "), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Box(Modifier.weight(1f))
            nextTest?.let { Text("Test ${it.epochDay.asDate("EEE d MMM")}", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun DayRow(day: TrainingDay, onClick: () -> Unit) {
    val mobilityOnly = day.items.isNotEmpty() && day.title.startsWith("Mobility")
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val badge = Modifier.size(38.dp)
        if (day.isRest) {
            Box(badge.border(1.dp, MaterialTheme.colorScheme.outline, CircleShape), contentAlignment = Alignment.Center) {
                Text(day.epochDay.asDate("d"), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Faint)
            }
        } else {
            val (bg, fg) = when {
                day.isBenchmark -> Warn.copy(alpha = 0.16f) to Warn
                mobilityOnly -> MaterialTheme.colorScheme.secondaryContainer to Leaf
                else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurface
            }
            Box(badge.background(bg, CircleShape), contentAlignment = Alignment.Center) {
                Text(day.epochDay.asDate("d"), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = fg)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(day.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            val detail = if (day.isRest) {
                "Recover"
            } else {
                day.items.take(2).joinToString(", ") { shortName(it.exercise.name) } + if (day.items.size > 2) " +${day.items.size - 2}" else ""
            }
            Text("${day.epochDay.asDate("EEE")} · $detail", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Faint)
    }
    HorizontalDivider(color = Hairline)
}

/** "Weighted Pull-ups (Max Strength)" → "Weighted Pull-ups" for compact lists. */
private fun shortName(name: String) = name.substringBefore(" (")

@Composable
fun EmptyProfile(onSetupProfile: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Mango", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
        Text(
            "Offline climbing periodization and whole-food nutrition.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        PrimaryButton("Set up profile", onSetupProfile, icon = null)
    }
}

@Composable
private fun EquipmentDialog(selected: List<Equipment>, onToggle: (Equipment) -> Unit, onDone: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("Available equipment") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "The whole schedule adapts instantly: missing equipment is replaced by the closest evidence-based alternative.",
                    style = MaterialTheme.typography.bodySmall,
                )
                EquipmentPicker(selected, onToggle)
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Done") } },
    )
}

// ============================ Session ============================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SessionScreen(
    week: Int,
    day: Int,
    onOpenExercise: (Int) -> Unit,
    onOpenTimer: () -> Unit,
    onBack: () -> Unit,
) {
    val vm = mangoViewModel(key = "session-$week-$day") { TrainingViewModel(it) }
    val s by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var benchmarkOpen by remember { mutableStateOf(false) }
    val d = s.day(week, day)
    val w = s.weeks.getOrNull(week)
    if (d == null || w == null) {
        Column(Modifier.padding(16.dp)) {
            BackBar(onBack)
            if (s.loading) CircularProgressIndicator()
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { BackBar(onBack) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "${d.epochDay.asDate("EEEE d MMM")} · Day ${d.dayNumber} · Week ${w.index + 1}",
                        fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(d.title, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 30.sp)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                        Pill(w.phase.label, phaseColor(w.phase))
                        if (w.isDeload) Pill("Deload −40%", Sky)
                        if (!d.isRest) Pill(sessionMeta(d), MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (d.isRest) {
                item { Text("Full rest – sleep, eat, let tendons remodel.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(d.items.size) { i -> ExerciseCard(i + 1, d.items[i]) { onOpenExercise(i) } }
        }
        if (d.isBenchmark || d.items.any { it.isTimed }) {
            BottomActionBar {
                if (d.isBenchmark) {
                    PrimaryButton("Enter test results", { benchmarkOpen = true }, icon = Icons.Filled.EmojiEvents)
                    Text(
                        "Warm up well, rest fully between attempts and stop at the first rep that isn't clean. Skip any test that doesn't feel right today.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    PrimaryButton("Start guided session", {
                        vm.startSession(context, d)
                        onOpenTimer()
                    })
                    Text(
                        "Runs every exercise in order with a 1-minute set-up between them. Tap an exercise for details.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                message?.let { Text(it, fontSize = 12.sp, color = Leaf) }
            }
        }
    }
    if (benchmarkOpen) {
        TestResultsDialog(
            tests = d.items.mapNotNull { MaxTest.forTest(it.exercise.id) },
            initial = s.baseline,
            bodyWeightKg = s.profile?.weightKg ?: 70.0,
            onDismiss = { benchmarkOpen = false },
            onSave = { results ->
                vm.saveResults(results)
                benchmarkOpen = false
            },
        )
    }
}

@Composable
private fun ExerciseCard(number: Int, p: Prescription, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        NumberBadge(number.toString(), categoryColor(p.exercise.category))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            SectionLabel(p.exercise.category.label)
            Text(shortName(p.exercise.name), fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Text(p.summary, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            p.substitutedFor?.let { Text("Replaces ${shortName(it)}", fontSize = 12.sp, color = Sky) }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (p.load.isNotEmpty()) Tag(p.load, emphasized = p.load.endsWith("kg"))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Faint)
        }
    }
}

// ============================ Exercise detail ============================

@Composable
fun ExerciseScreen(week: Int, day: Int, index: Int, onOpenTimer: () -> Unit, onBack: () -> Unit) {
    val vm = mangoViewModel(key = "exercise-$week-$day-$index") { TrainingViewModel(it) }
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val d = s.day(week, day)
    val p = d?.items?.getOrNull(index)
    if (d == null || p == null) {
        Column(Modifier.padding(16.dp)) {
            BackBar(onBack)
            if (s.loading) CircularProgressIndicator()
        }
        return
    }
    val ex = p.exercise
    Column(Modifier.fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp, 4.dp, 16.dp, 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            BackBar(onBack, trailing = "${index + 1} of ${d.items.size}")
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SectionLabel(ex.category.label, color = categoryColor(ex.category))
                Text(ex.name, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 30.sp)
                p.substitutedFor?.let { Text("Replaces $it – equipment not available", fontSize = 13.sp, color = Sky) }
            }
            val work = Periodization.workLabel(ex)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile("Sets", p.sets.toString(), Modifier.weight(1f))
                    if (p.workSec > 0) {
                        StatTile(if (p.reps > 1) "$work × ${p.reps}" else work, formatDuration(p.workSec), Modifier.weight(1f))
                    } else {
                        StatTile("Reps", p.reps.toString(), Modifier.weight(1f))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatTile(
                        if (p.repRestSec > 0) "Rest rep / set" else "Rest",
                        when {
                            p.repRestSec > 0 -> "${formatDuration(p.repRestSec)} / ${formatDuration(p.setRestSec)}"
                            p.setRestSec > 0 -> formatDuration(p.setRestSec)
                            else -> "—"
                        },
                        Modifier.weight(1f),
                    )
                    StatTile(
                        p.intensity?.let { "Load · ${(it * 100).toInt()} %" } ?: "Load",
                        p.load.ifEmpty { "—" },
                        Modifier.weight(1f),
                        highlight = p.load.endsWith("kg"),
                    )
                }
                if (p.loadText.contains("no weights") || p.loadText.contains("assist")) {
                    Text(p.loadText, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (ex.cues.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel("How to")
                    ex.cues.forEach { cue ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(Icons.Filled.Check, contentDescription = null, tint = Leaf, modifier = Modifier.size(18.dp))
                            Text(cue, fontSize = 15.sp, lineHeight = 21.sp)
                        }
                    }
                }
            }
            GroupedCard {
                ExpandableRow("Why this exercise") { Text(ex.description, fontSize = 14.sp, lineHeight = 20.sp) }
                HorizontalDivider(color = Hairline)
                val needs = ex.requires.joinToString(" + ") { group -> group.joinToString(" or ") { it.label } }
                ExpandableRow("Equipment", trailing = ex.requires.firstOrNull()?.firstOrNull()?.label ?: "None") {
                    Text(needs.ifEmpty { "No equipment needed." }, fontSize = 14.sp)
                }
                HorizontalDivider(color = Hairline)
                ExpandableRow("Sources") { Text(ex.source, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        if (p.isTimed) {
            BottomActionBar {
                PrimaryButton("Start timer", {
                    vm.startExercise(context, p)
                    onOpenTimer()
                }, icon = Icons.Filled.Timer)
            }
        }
    }
}

// ============================ Long-term plan ============================

private data class PlanBlock(val phase: Phase, val cycle: Int, val weeks: List<TrainingWeek>)

@Composable
fun PlanScreen(onOpenSession: (week: Int, day: Int) -> Unit, onBack: () -> Unit) {
    val vm = mangoViewModel(key = "plan") { TrainingViewModel(it) }
    val s by vm.state.collectAsStateWithLifecycle()
    val profile = s.profile
    // Consecutive weeks of the same phase form a block (4-week blocks in evergreen mode).
    val blocks = remember(s.weeks) {
        s.weeks.fold(mutableListOf<PlanBlock>()) { acc, w ->
            val last = acc.lastOrNull()
            if (last != null && last.phase == w.phase && last.cycle == w.cycle) {
                acc[acc.lastIndex] = last.copy(weeks = last.weeks + w)
            } else {
                acc += PlanBlock(w.phase, w.cycle, listOf(w))
            }
            acc
        }
    }
    val currentBlock = blocks.indexOfFirst { b -> b.weeks.any { it.index == s.currentWeek } }
    val openBlocks = remember(currentBlock) { mutableStateMapOf(currentBlock to true) }
    val openWeeks = remember { mutableStateMapOf<Int, Boolean>() }

    LazyColumn(contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { BackBar(onBack) }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Your plan", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                if (profile != null) {
                    Text(
                        (if (profile.programMode == ProgramMode.EVERGREEN) "Evergreen 16-week cycle" else "${profile.programWeeks}-week program") +
                            " · ${profile.goal.label} · tap a block, then a week, for details",
                        fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        blocks.forEachIndexed { bi, block ->
            if (profile?.programMode == ProgramMode.EVERGREEN && (bi == 0 || blocks[bi - 1].cycle != block.cycle)) {
                item(key = "cycle-${block.cycle}") { SectionLabel("Cycle ${block.cycle + 1}", Modifier.padding(top = 8.dp)) }
            }
            item(key = "block-$bi") {
                val status = when {
                    block.weeks.last().index < s.currentWeek -> "Done"
                    bi == currentBlock -> "Now"
                    bi == currentBlock + 1 -> "Next"
                    else -> "Later"
                }
                PlanBlockCard(
                    block = block,
                    status = status,
                    open = openBlocks[bi] == true,
                    onToggle = { openBlocks[bi] = openBlocks[bi] != true },
                ) {
                    block.weeks.forEach { w ->
                        PlanWeekRow(w, s, openWeeks[w.index] == true, { openWeeks[w.index] = openWeeks[w.index] != true }, onOpenSession)
                    }
                }
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp)).padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.EmojiEvents, contentDescription = null, tint = Warn)
                Text(
                    "Each block ends with a test on Sunday of its deload week. Your results set the loads for the next block.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PlanBlockCard(block: PlanBlock, status: String, open: Boolean, onToggle: () -> Unit, weeks: @Composable () -> Unit) {
    val first = block.weeks.first()
    val last = block.weeks.last()
    val color = phaseColor(block.phase)
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, shape)
            .then(if (status == "Now") Modifier.border(1.dp, Color(0xFF5A4220), shape) else Modifier),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(6.dp, 56.dp).background(color.copy(alpha = if (status == "Done") 0.5f else 1f), RoundedCornerShape(3.dp)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(block.phase.label, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Weeks ${first.index + 1}–${last.index + 1} · ${first.startEpochDay.asDate("d MMM")} – ${(last.startEpochDay + 6).asDate("d MMM")}",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(block.phase.focus, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Pill(status, if (status == "Now") MangoOrange else if (status == "Done") Leaf else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp)) { weeks() }
        }
    }
}

@Composable
private fun PlanWeekRow(w: TrainingWeek, s: TrainingState, open: Boolean, onToggle: () -> Unit, onOpenSession: (Int, Int) -> Unit) {
    val done = w.index < s.currentWeek
    val now = w.index == s.currentWeek
    val color = phaseColor(w.phase)
    HorizontalDivider(color = Hairline)
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(12.dp)
                .background(if (done) color.copy(alpha = 0.6f) else if (now) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)
                .border(2.dp, if (now) MaterialTheme.colorScheme.onSurface else color, CircleShape),
        )
        Text("Week ${w.index + 1}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(
            w.startEpochDay.asDate("d MMM") + if (w.isDeload) " · volume −40 %" else "",
            fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
        )
        when {
            w.days.any { it.isBenchmark } -> Pill("Test days", Warn)
            now -> Pill("This week", MangoOrange)
            done -> Pill("Done", Leaf)
        }
        Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, tint = Faint)
    }
    AnimatedVisibility(open) {
        Column(Modifier.padding(start = 22.dp, bottom = 6.dp)) {
            w.days.forEachIndexed { i, d ->
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = !d.isRest) { onOpenSession(w.index, i) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(d.epochDay.asDate("EEE"), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(width = 44.dp, height = 18.dp))
                    Text(d.title, fontSize = 14.sp, modifier = Modifier.weight(1f), color = if (d.isRest) Faint else MaterialTheme.colorScheme.onSurface)
                    if (!d.isRest) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = Faint, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

// ============================ Test days ============================

/**
 * Results of a test day. Single-number tests take the value; rep-max tests take the load and the reps and
 * show the estimated 1RM (Epley). Leave a test empty to skip it – its previous max stays.
 */
@Composable
private fun TestResultsDialog(
    tests: List<MaxTest>,
    initial: StrengthBaseline?,
    bodyWeightKg: Double,
    onDismiss: () -> Unit,
    onSave: (Map<String, Double>) -> Unit,
) {
    val values = remember { mutableStateMapOf<MaxTest, Double>() }
    val reps = remember { mutableStateMapOf<MaxTest, Double>() }
    fun set(map: MutableMap<MaxTest, Double>, t: MaxTest, v: Double?) {
        if (v == null) map.remove(t) else map[t] = v
    }
    fun result(t: MaxTest): Double? {
        val v = values[t] ?: return null
        return when (t.input) {
            MaxTest.Input.BODYWEIGHT_RM -> reps[t]?.takeIf { it >= 1 }?.let { MaxTests.dipAdded1RM(v, it.toInt(), bodyWeightKg) }
            MaxTest.Input.WEIGHT_RM -> reps[t]?.takeIf { it >= 1 && v > 0 }?.let { MaxTests.press1RM(v, it.toInt()) }
            MaxTest.Input.REPS, MaxTest.Input.SECONDS -> v.takeIf { it > 0 }
            MaxTest.Input.ADDED_KG -> v
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Test results") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Fill in what you tested; leave the rest empty. Loads, reps and holds adapt from tomorrow.", style = MaterialTheme.typography.bodySmall)
                tests.forEach { t ->
                    val previous = initial?.max(t)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(t.label, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        when (t.input) {
                            MaxTest.Input.ADDED_KG -> OptionalNumberField("Added load (negative = assisted)", values[t], { set(values, t, it) }, "kg", allowNegative = true)
                            MaxTest.Input.REPS -> OptionalNumberField("Reps", values[t], { set(values, t, it) }, "reps")
                            MaxTest.Input.SECONDS -> OptionalNumberField("Hold", values[t], { set(values, t, it) }, "s")
                            MaxTest.Input.BODYWEIGHT_RM, MaxTest.Input.WEIGHT_RM -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OptionalNumberField(
                                    if (t.input == MaxTest.Input.BODYWEIGHT_RM) "Added load" else "Weight",
                                    values[t], { set(values, t, it) }, "kg", Modifier.weight(1f), allowNegative = t.input == MaxTest.Input.BODYWEIGHT_RM,
                                )
                                OptionalNumberField("Reps", reps[t], { set(reps, t, it) }, "reps", Modifier.weight(1f))
                            }
                        }
                        val now = result(t)
                        Text(
                            buildString {
                                if (now != null && t.input in setOf(MaxTest.Input.BODYWEIGHT_RM, MaxTest.Input.WEIGHT_RM)) append("≈ 1RM ${maxText(t, now)} · ")
                                append(if (previous != null) "previous ${maxText(t, previous)}" else "not tested yet")
                            },
                            fontSize = 12.sp, color = if (now != null && previous != null && now > previous) Leaf else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(tests.mapNotNull { t -> result(t)?.let { t.key to it } }.toMap()) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Number field that can stay empty (test skipped). */
@Composable
private fun OptionalNumberField(
    label: String,
    value: Double?,
    onChange: (Double?) -> Unit,
    suffix: String,
    modifier: Modifier = Modifier.fillMaxWidth(),
    allowNegative: Boolean = false,
) {
    var text by remember { mutableStateOf(value?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() } ?: "") }
    OutlinedTextField(
        value = text,
        onValueChange = { v ->
            val clean = v.replace(',', '.').filter { it.isDigit() || it == '.' || (allowNegative && it == '-') }
            text = clean
            onChange(clean.toDoubleOrNull())
        },
        label = { Text(label) },
        suffix = { Text(suffix) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

fun maxText(t: MaxTest, v: Double): String {
    val n = if (v % 1.0 == 0.0) v.toInt().toString() else "%.1f".format(v)
    return when (t.input) {
        MaxTest.Input.ADDED_KG, MaxTest.Input.BODYWEIGHT_RM -> (if (v >= 0) "+" else "") + "$n kg"
        MaxTest.Input.WEIGHT_RM -> "$n kg"
        MaxTest.Input.REPS -> if (v == 1.0) "1 rep" else "$n reps"
        MaxTest.Input.SECONDS -> "$n s"
    }
}

/** Latest max of every test, with the change since the previous test. */
@Composable
private fun MaxesCard(rows: List<MaxRow>) {
    SectionCard {
        SectionLabel("Your maxes", color = Warn)
        rows.forEach { r ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.test.label, fontSize = 14.sp, modifier = Modifier.weight(1f))
                r.previous?.let { prev ->
                    val diff = r.value - prev
                    if (abs(diff) >= 0.05) {
                        Text(
                            (if (diff > 0) "▲ " else "▼ ") + maxText(r.test, abs(diff)).removePrefix("+"),
                            fontSize = 12.sp, color = if (diff > 0) Leaf else Danger, modifier = Modifier.padding(end = 8.dp),
                        )
                    }
                }
                Text(maxText(r.test, r.value), fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text("Tested at the end of every deload week; the next block's loads, reps and holds follow.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
