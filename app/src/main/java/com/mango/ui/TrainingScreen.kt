package com.mango.ui

import android.content.Context
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mango.data.Equipment
import com.mango.data.ProgramMode
import com.mango.data.Repository
import com.mango.data.UserProfile
import com.mango.domain.Periodization
import com.mango.domain.Prescription
import com.mango.domain.StrengthBaseline
import com.mango.domain.TrainingDay
import com.mango.domain.TrainingWeek
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
)

class TrainingViewModel(repo: Repository) : MangoViewModel(repo) {
    val state = combine(repo.profile, repo.exercises, repo.benchmarks) { p, exercises, benchmarks ->
        val today = repo.today()
        if (p == null || exercises.isEmpty()) {
            TrainingState(loading = p != null, profile = p, today = today)
        } else {
            val current = Periodization.weekIndexOn(p, today)
            val blockStart = p.programStartEpochDay + (current.coerceAtLeast(0) / Periodization.BLOCK_WEEKS) * Periodization.BLOCK_WEEKS * 7L
            TrainingState(
                loading = false,
                profile = p,
                weeks = Periodization.build(p, exercises.associateBy { it.id }, benchmarks, today),
                currentWeek = current,
                today = today,
                baseline = Periodization.baselineFor(p, benchmarks, blockStart),
            )
        }
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrainingState())

    fun toggleEquipment(e: Equipment) = launchSafe {
        val current = state.value.profile?.equipment ?: return@launchSafe
        repo.updateEquipment(if (e in current) current - e else current + e)
    }

    fun saveBenchmark(fingerAddedKg: Double, pullUpAddedKg: Double) = launchSafe {
        repo.addBenchmark(fingerAddedKg, pullUpAddedKg)
        toast("Benchmark saved – loads for the next block recalculated")
    }

    fun startTimer(context: Context, p: Prescription) {
        WorkoutTimerService.start(context, p.exercise.name, Periodization.intervals(p))
    }
}

@Composable
fun TrainingScreen(onOpenTimer: () -> Unit, onSetupProfile: () -> Unit) {
    val vm = mangoViewModel { TrainingViewModel(it) }
    val s by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }

    when {
        s.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        s.profile == null -> EmptyProfile(onSetupProfile)
        else -> Box(Modifier.fillMaxSize()) {
            val expanded = remember { mutableStateMapOf(s.currentWeek to true) }
            var benchmarkDay by remember { mutableStateOf<TrainingDay?>(null) }
            var editEquipment by remember { mutableStateOf(false) }
            val listState = rememberLazyListState(initialFirstVisibleItemIndex = (s.currentWeek + 1).coerceIn(0, s.weeks.size))
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item { TrainingHeader(s, onEquipment = { editEquipment = true }) }
                items(s.weeks, key = { it.index }) { week ->
                    WeekCard(
                        week = week,
                        isCurrent = week.index == s.currentWeek,
                        expanded = expanded[week.index] == true,
                        today = s.today,
                        onToggle = { expanded[week.index] = expanded[week.index] != true },
                        onTimer = {
                            vm.startTimer(context, it)
                            onOpenTimer()
                        },
                        onBenchmark = { benchmarkDay = it },
                    )
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
            if (editEquipment) {
                AlertDialog(
                    onDismissRequest = { editEquipment = false },
                    title = { Text("Available equipment") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                "The whole schedule adapts instantly: missing equipment is replaced by the closest evidence-based alternative.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            EquipmentPicker(s.profile?.equipment.orEmpty(), vm::toggleEquipment)
                        }
                    },
                    confirmButton = { TextButton(onClick = { editEquipment = false }) { Text("Done") } },
                )
            }
            benchmarkDay?.let { day ->
                BenchmarkDialog(
                    tests = day.items.map { it.exercise.id }.toSet(),
                    initial = s.baseline,
                    onDismiss = { benchmarkDay = null },
                    onSave = { finger, pull ->
                        vm.saveBenchmark(finger, pull)
                        benchmarkDay = null
                    },
                )
            }
        }
    }
}

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
        Button(onClick = onSetupProfile) { Text("Set up profile") }
    }
}

@Composable
private fun TrainingHeader(s: TrainingState, onEquipment: () -> Unit) {
    val p = s.profile ?: return
    val week = s.weeks.getOrNull(s.currentWeek)
    SectionCard {
        if (week == null) {
            Text(
                if (s.currentWeek < 0) "Program starts ${p.programStartEpochDay.asDate()}" else "Program complete 🎉",
                style = MaterialTheme.typography.titleLarge,
            )
            Text("Restart or extend it from your profile.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Week ${week.index + 1}", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (week.isDeload) Pill("DELOAD", Sky)
                Pill(p.goal.label, Leaf)
            }
            Text(week.phase.label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text(week.phase.focus, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Block week ${week.weekInBlock}/4" + if (p.programMode == ProgramMode.EVERGREEN) " · Cycle ${week.cycle + 1}" else " · of ${p.programWeeks}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        s.baseline?.let {
            HorizontalDivider()
            Text(
                "Current baseline: 20 mm edge ${signed(it.fingerAddedKg)} kg · Pull-up ${signed(it.pullAddedKg)} kg @ ${it.bodyWeightKg.fmt(1)} kg BW",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Equipment: " + p.equipment.size.let { if (it == 0) "none (bodyweight only)" else "$it item(s)" },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onEquipment) {
                Icon(Icons.Filled.FitnessCenter, contentDescription = null)
                Text("  Change")
            }
        }
    }
}

private fun signed(v: Double) = if (v >= 0) "+${v.fmt(1)}" else v.fmt(1)

@Composable
private fun WeekCard(
    week: TrainingWeek,
    isCurrent: Boolean,
    expanded: Boolean,
    today: Long,
    onToggle: () -> Unit,
    onTimer: (Prescription) -> Unit,
    onBenchmark: (TrainingDay) -> Unit,
) {
    SectionCard {
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Week ${week.index + 1} · ${week.phase.label}",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                Text(week.startEpochDay.asDate("d MMM") + " – " + (week.startEpochDay + 6).asDate("d MMM"), style = MaterialTheme.typography.bodySmall)
            }
            if (week.isDeload) Pill("Deload −40%", Sky)
            Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
        }
        AnimatedVisibility(expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                week.days.forEach { day -> DayBlock(day, day.epochDay == today, onTimer, onBenchmark) }
            }
        }
    }
}

@Composable
private fun DayBlock(day: TrainingDay, isToday: Boolean, onTimer: (Prescription) -> Unit, onBenchmark: (TrainingDay) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Day ${day.dayNumber} · ${day.epochDay.asDate("EEE d")} · ${day.title}",
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                color = when {
                    day.isBenchmark -> Warn
                    isToday -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
            if (isToday) Pill("TODAY")
        }
        if (day.isRest) {
            Text("Full rest – sleep, eat, let tendons remodel.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        day.items.forEach { ExerciseRow(it, onTimer) }
        if (day.isBenchmark) {
            Button(onClick = { onBenchmark(day) }) { Text("Enter benchmark results") }
        }
    }
}

@Composable
private fun ExerciseRow(p: Prescription, onTimer: (Prescription) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(p.exercise.name, style = MaterialTheme.typography.bodyLarge)
                p.substitutedFor?.let { Text("Replaces $it (equipment)", style = MaterialTheme.typography.labelSmall, color = Sky) }
                Text(p.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (p.loadText.isNotEmpty()) Text(p.loadText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            if (p.isTimed) {
                FilledTonalIconButton(onClick = { onTimer(p) }) { Icon(Icons.Filled.Timer, contentDescription = "Start timer") }
            }
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(p.exercise.description, style = MaterialTheme.typography.bodySmall)
                p.exercise.cues.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = Leaf) }
                if (p.exercise.requires.isNotEmpty()) {
                    Text(
                        "Needs: " + p.exercise.requires.joinToString(" + ") { group -> group.joinToString(" or ") { it.label } },
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                Text("Source: ${p.exercise.source}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun BenchmarkDialog(tests: Set<String>, initial: StrengthBaseline?, onDismiss: () -> Unit, onSave: (Double, Double) -> Unit) {
    var finger by remember { mutableStateOf(initial?.fingerAddedKg ?: 0.0) }
    var pull by remember { mutableStateOf(initial?.pullAddedKg ?: 0.0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Benchmark results") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Added load (negative = assisted). Next block's targets are recalculated from these.", style = MaterialTheme.typography.bodySmall)
                // Only tests possible with the current equipment are asked; the other value keeps its baseline.
                if ("bench_max_hang_5s" in tests) NumberField("5 s max hang, 20 mm", finger, { finger = it }, allowNegative = true, suffix = "kg")
                if ("bench_pullup_1rm" in tests) NumberField("Weighted pull-up 1RM", pull, { pull = it }, allowNegative = true, suffix = "kg")
            }
        },
        confirmButton = { TextButton(onClick = { onSave(finger, pull) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
