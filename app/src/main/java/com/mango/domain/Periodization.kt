package com.mango.domain

import com.mango.data.Benchmark
import com.mango.data.Equipment
import com.mango.data.Exercise
import com.mango.data.ExerciseCategory
import com.mango.data.Goal
import com.mango.data.LoadBasis
import com.mango.data.ProgramMode
import com.mango.data.UserProfile
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

enum class Phase(val label: String, val focus: String) {
    BASE("Base / Capacity", "Volume, capillarisation and connective-tissue conditioning"),
    MAX_STRENGTH("Max Strength", "Neuromuscular recruitment at 80-90 % intensity"),
    POWER_ENDURANCE("Power / Endurance", "Goal-specific power or anaerobic capacity"),
    PEAK("Taper / Peak", "Volume reduced ~50 %, intensity maintained"),
}

data class Prescription(
    val exercise: Exercise,
    val sets: Int,
    val reps: Int,
    val workSec: Int,
    val repRestSec: Int,
    val setRestSec: Int,
    val intensity: Double?,
    val loadText: String,
    /** Name of the planned exercise this one replaces because its equipment isn't available. */
    val substitutedFor: String? = null,
    /** Compact load for tags and tiles: "+6.5 kg", "−5 kg", "BW", "RPE 7-8" or "". */
    val load: String = "",
) {
    val isTimed: Boolean get() = workSec > 0 || setRestSec > 0

    val isTest: Boolean get() = exercise.category == ExerciseCategory.BENCHMARK

    val summary: String
        get() = buildString {
            append(
                when {
                    isTest && sets == 1 -> "1 all-out set"
                    isTest && exercise.id == MaxTest.FRONT_LEVER.testId -> "Best of $sets attempts"
                    isTest -> "Up to $sets attempts"
                    workSec > 0 && reps > 1 -> "$sets × $reps × ${formatDuration(workSec)}"
                    workSec > 0 -> "$sets × ${formatDuration(workSec)}"
                    else -> "$sets × $reps reps"
                },
            )
            if (repRestSec > 0) append(" (${formatDuration(repRestSec)} off)")
            if (setRestSec > 0) append(" · rest ${formatDuration(setRestSec)}")
        }
}

data class TrainingDay(
    val epochDay: Long,
    val dayNumber: Int,
    val title: String,
    val items: List<Prescription>,
    val isBenchmark: Boolean = false,
    val isRest: Boolean = false,
)

data class TrainingWeek(
    val index: Int,
    val cycle: Int,
    val weekInBlock: Int,
    val phase: Phase,
    val isDeload: Boolean,
    val startEpochDay: Long,
    val days: List<TrainingDay>,
)

data class StrengthBaseline(
    val fingerAddedKg: Double,
    val pullAddedKg: Double,
    val bodyWeightKg: Double,
    /** Tested maxes (all tests, including fingers and pull-ups once tested). */
    val maxes: Map<MaxTest, Double> = emptyMap(),
) {
    fun max(test: MaxTest?): Double? = when (test) {
        null -> null
        MaxTest.FINGER -> fingerAddedKg
        MaxTest.PULL -> pullAddedKg
        else -> maxes[test]
    }
}

@Serializable
data class TimerInterval(
    val label: String,
    val detail: String,
    val seconds: Int,
    val isWork: Boolean,
    val exercise: String = "",
    val set: Int = 0,
    val sets: Int = 0,
    val cues: List<String> = emptyList(),
)

fun formatDuration(sec: Int): String =
    if (sec < 60) "${sec}s" else "%d:%02d".format(sec / 60, sec % 60)

/**
 * Deterministic block periodization.
 *  - Fixed mode: phase lengths from 35/35/20/10 % ratios (largest-remainder rounding, ≥ 1 week each).
 *  - Evergreen mode: endless 16-week cycle of four 4-week blocks.
 *  - Every 4th week is a deload: -40 % volume, intensity held (Pritchard et al. 2015 Strength Cond J 37:72;
 *    tendon collagen remodelling – Magnusson, Langberg & Kjaer 2010 Nat Rev Rheumatol 6:262).
 *  - Taper volume -50 % with intensity maintained (Bosquet et al. 2007 Med Sci Sports Exerc 39:1358).
 *  - Test days at the end of every deload week (day 5: front lever, pull-ups, dips, press, push-ups; day 7:
 *    max hang and weighted pull-up), plus a calibration in week 1 for any max never tested before the program
 *    started. Results drive loads, reps and hold times from the next day on.
 */
object Periodization {
    const val BLOCK_WEEKS = 4
    const val EVERGREEN_WEEKS = 16
    private const val DELOAD_VOLUME = 0.6
    private const val TAPER_VOLUME = 0.5
    private const val WEEKLY_INTENSITY_STEP = 0.025

    val PHASE_RATIOS: List<Pair<Phase, Double>> = listOf(
        Phase.BASE to 0.35,
        Phase.MAX_STRENGTH to 0.35,
        Phase.POWER_ENDURANCE to 0.20,
        Phase.PEAK to 0.10,
    )

    fun allocateWeeks(totalWeeks: Int): Map<Phase, Int> {
        val n = totalWeeks.coerceAtLeast(PHASE_RATIOS.size)
        val raw = PHASE_RATIOS.map { (phase, r) -> phase to n * r }
        val alloc = raw.associate { (phase, v) -> phase to max(1, floor(v).toInt()) }.toMutableMap()
        var diff = n - alloc.values.sum()
        // Rounded so floating-point noise can't break ties; stable sort keeps ratio order for equal remainders.
        val byFraction = raw.sortedByDescending { (_, v) -> ((v - floor(v)) * 1e6).roundToLong() }.map { it.first }
        var i = 0
        while (diff > 0) {
            val phase = byFraction[i++ % byFraction.size]
            alloc[phase] = alloc.getValue(phase) + 1
            diff--
        }
        while (diff < 0) {
            val phase = alloc.filter { it.value > 1 }.maxBy { it.value }.key
            alloc[phase] = alloc.getValue(phase) - 1
            diff++
        }
        return PHASE_RATIOS.associate { (phase, _) -> phase to alloc.getValue(phase) }
    }

    fun weekIndexOn(profile: UserProfile, epochDay: Long): Int =
        Math.floorDiv(epochDay - profile.programStartEpochDay, 7L).toInt()

    fun isDeload(weekIndex: Int): Boolean = (weekIndex + 1) % BLOCK_WEEKS == 0

    /** Phase of a program week, or null if the week is outside a fixed-length program. */
    fun phaseForWeek(profile: UserProfile, weekIndex: Int): Phase? {
        if (weekIndex < 0) return null
        return when (profile.programMode) {
            ProgramMode.EVERGREEN -> PHASE_RATIOS[(weekIndex % EVERGREEN_WEEKS) / BLOCK_WEEKS].first
            ProgramMode.FIXED -> {
                var end = 0
                allocateWeeks(profile.programWeeks).entries.firstOrNull { (_, weeks) ->
                    end += weeks
                    weekIndex < end
                }?.key
            }
        }
    }

    /** Phase used for nutrition on a given date (BASE before/after a fixed program). */
    fun phaseOn(profile: UserProfile, epochDay: Long): Phase =
        phaseForWeek(profile, weekIndexOn(profile, epochDay)) ?: Phase.BASE

    fun weeksToShow(profile: UserProfile, today: Long): Int = when (profile.programMode) {
        ProgramMode.FIXED -> profile.programWeeks.coerceAtLeast(PHASE_RATIOS.size)
        ProgramMode.EVERGREEN -> {
            val current = weekIndexOn(profile, today).coerceAtLeast(0)
            (current / EVERGREEN_WEEKS + 2) * EVERGREEN_WEEKS
        }
    }

    /** Maxes recorded on or before [day], else the profile's estimates. */
    fun baselineFor(profile: UserProfile, benchmarks: List<Benchmark>, day: Long): StrengthBaseline =
        MaxTests.baselineOn(profile, benchmarks, day + 1)

    fun build(
        profile: UserProfile,
        exercises: Map<String, Exercise>,
        benchmarks: List<Benchmark>,
        today: Long,
    ): List<TrainingWeek> {
        val calibrate = MaxTests.untestedAt(benchmarks, profile.programStartEpochDay)
        return (0 until weeksToShow(profile, today)).map { w -> buildWeek(profile, w, exercises, benchmarks, if (w == 0) calibrate else emptySet()) }
    }

    private fun buildWeek(
        profile: UserProfile,
        w: Int,
        exercises: Map<String, Exercise>,
        benchmarks: List<Benchmark>,
        calibrate: Set<MaxTest>,
    ): TrainingWeek {
        val phase = phaseForWeek(profile, w) ?: Phase.PEAK
        val weekInBlock = w % BLOCK_WEEKS + 1
        val start = profile.programStartEpochDay + w * 7L
        return TrainingWeek(
            index = w,
            cycle = w / EVERGREEN_WEEKS,
            weekInBlock = weekInBlock,
            phase = phase,
            isDeload = isDeload(w),
            startEpochDay = start,
            days = buildDays(
                phase, profile.goal, weekInBlock, start, { day -> MaxTests.baselineOn(profile, benchmarks, day) },
                exercises, profile.equipment.toSet(), calibrate,
            ),
        )
    }

    fun loadText(
        ex: Exercise,
        intensity: Double?,
        baseline: StrengthBaseline,
        equipment: Set<Equipment> = Equipment.entries.toSet(),
    ): String = when (ex.loadBasis) {
        LoadBasis.FINGER_1RM -> {
            val pct = intensity ?: 0.0
            if (Equipment.WEIGHTS !in equipment && targetAddedKg(pct, baseline.bodyWeightKg, baseline.fingerAddedKg) > 0) {
                // Without added weight, raise intensity by reducing edge depth instead (Lattice Training).
                "Bodyweight · ${(pct * 100).roundToInt()}% – no weights: use a smaller edge until the last 2-3 s are hard"
            } else {
                addedLoadText(pct, baseline.bodyWeightKg, baseline.fingerAddedKg)
            }
        }
        LoadBasis.PULL_1RM -> addedLoadText(intensity ?: 0.0, baseline.bodyWeightKg, baseline.pullAddedKg)
        LoadBasis.DIP_1RM -> baseline.max(MaxTest.DIP)?.let { max ->
            val pct = intensity ?: 0.0
            if (Equipment.WEIGHTS !in equipment && targetAddedKg(pct, baseline.bodyWeightKg, max) > 0) {
                "Bodyweight · ${(pct * 100).roundToInt()}% – no weights: slow 3 s lowering, pause at the bottom"
            } else {
                addedLoadText(pct, baseline.bodyWeightKg, max)
            }
        } ?: UNTESTED
        LoadBasis.PRESS_1RM -> baseline.max(MaxTest.PRESS)?.let { max ->
            "≈ ${fmtKg(pressKg(intensity ?: 0.0, max))} kg (barbell, or both dumbbells together) · ${((intensity ?: 0.0) * 100).roundToInt()}%"
        } ?: UNTESTED
        LoadBasis.PULLUP_REPS -> baseline.max(MaxTest.PULLUP_REPS)?.let { "Bodyweight · ${((intensity ?: 0.0) * 100).roundToInt()}% of your ${it.roundToInt()} max reps" } ?: "Bodyweight"
        LoadBasis.PUSHUP_REPS -> baseline.max(MaxTest.PUSHUP_REPS)?.let { "Bodyweight · ${((intensity ?: 0.0) * 100).roundToInt()}% of your ${it.roundToInt()} max push-ups" } ?: "Bodyweight"
        LoadBasis.FL_HOLD -> baseline.max(MaxTest.FRONT_LEVER)?.let { "Bodyweight · ${((intensity ?: 0.0) * 100).roundToInt()}% of your ${it.roundToInt()} s tuck hold" } ?: "Bodyweight"
        LoadBasis.BODYWEIGHT -> "Bodyweight"
        LoadBasis.RPE -> "RPE 7-8 (2-3 reps in reserve)"
        LoadBasis.NONE -> ""
    }

    private const val UNTESTED = "RPE 7-8 (2-3 reps in reserve) – exact load after your next test day"

    /** Press load = %1RM of the lifted weight, rounded to 1 kg (dumbbell steps). */
    fun pressKg(intensity: Double, oneRm: Double): Double = (intensity * oneRm).roundToInt().toDouble()

    /** Target added load = %1RM × (bodyweight + 1RM added) − bodyweight, rounded to 0.5 kg. */
    fun targetAddedKg(intensity: Double, bodyWeightKg: Double, maxAddedKg: Double): Double =
        ((intensity * (bodyWeightKg + maxAddedKg) - bodyWeightKg) * 2).roundToInt() / 2.0

    private fun addedLoadText(intensity: Double, bw: Double, maxAdded: Double): String {
        val added = targetAddedKg(intensity, bw, maxAdded)
        val pct = (intensity * 100).roundToInt()
        return if (added >= 0) "BW + ${fmtKg(added)} kg · $pct%" else "BW − ${fmtKg(abs(added))} kg assist · $pct%"
    }

    private fun fmtKg(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()

    private fun signedKg(v: Double) = if (v >= 0) "+${fmtKg(v)} kg" else "−${fmtKg(abs(v))} kg"

    fun shortLoad(ex: Exercise, intensity: Double?, baseline: StrengthBaseline, equipment: Set<Equipment>): String = when (ex.loadBasis) {
        LoadBasis.FINGER_1RM -> {
            val added = targetAddedKg(intensity ?: 0.0, baseline.bodyWeightKg, baseline.fingerAddedKg)
            if (Equipment.WEIGHTS !in equipment && added > 0) "BW" else signedKg(added)
        }
        LoadBasis.PULL_1RM -> signedKg(targetAddedKg(intensity ?: 0.0, baseline.bodyWeightKg, baseline.pullAddedKg))
        LoadBasis.DIP_1RM -> baseline.max(MaxTest.DIP)?.let { max ->
            val added = targetAddedKg(intensity ?: 0.0, baseline.bodyWeightKg, max)
            if (Equipment.WEIGHTS !in equipment && added > 0) "BW" else signedKg(added)
        } ?: "RPE 7-8"
        LoadBasis.PRESS_1RM -> baseline.max(MaxTest.PRESS)?.let { "${fmtKg(pressKg(intensity ?: 0.0, it))} kg" } ?: "RPE 7-8"
        LoadBasis.PULLUP_REPS, LoadBasis.PUSHUP_REPS, LoadBasis.FL_HOLD -> "BW"
        LoadBasis.BODYWEIGHT -> "BW"
        LoadBasis.RPE -> "RPE 7-8"
        LoadBasis.NONE -> ""
    }

    /** What the athlete does during a work interval, shown big on the timer. */
    fun workLabel(ex: Exercise): String = when {
        ex.id.startsWith("fb_edge") -> "LIFT"
        ex.category == ExerciseCategory.FINGERBOARD || ex.id == "bench_max_hang_5s" -> "HANG"
        ex.category == ExerciseCategory.CLIMBING -> "CLIMB"
        ex.category == ExerciseCategory.CORE || ex.category.isMobility -> if (ex.workSec > 0) "HOLD" else "WORK"
        else -> "WORK"
    }

    fun intervals(p: Prescription): List<TimerInterval> {
        val ex = p.exercise
        val cues = ex.cues.take(3)
        fun iv(label: String, detail: String, sec: Int, work: Boolean, set: Int) =
            TimerInterval(label, detail, sec, work, ex.name, set, p.sets, cues)
        val work = workLabel(ex)
        val out = mutableListOf(iv("GET READY", ex.name, 10, false, 1))
        for (s in 1..p.sets) {
            if (p.workSec > 0) {
                for (r in 1..p.reps) {
                    val rep = if (p.reps > 1) " · rep $r/${p.reps}" else ""
                    out += iv(work, "Set $s/${p.sets}$rep", p.workSec, true, s)
                    if (r < p.reps && p.repRestSec > 0) out += iv("REST", "Next: rep ${r + 1}", p.repRestSec, false, s)
                }
            } else {
                out += iv(work, "Set $s/${p.sets} · ${p.reps} reps", max(20, p.reps * 4), true, s)
            }
            if (s < p.sets && p.setRestSec > 0) out += iv("REST", "Next: set ${s + 1}", p.setRestSec, false, s + 1)
        }
        return out
    }

    /** A whole session back to back: one countdown, then a 60 s set-up break before each next exercise. */
    fun sessionIntervals(items: List<Prescription>): List<TimerInterval> =
        items.filter { it.isTimed }.flatMapIndexed { i, p ->
            val single = intervals(p)
            if (i == 0) single else listOf(single.first().copy(label = "SET UP", detail = "Next: ${p.exercise.name}", seconds = 60)) + single.drop(1)
        }

    /** Rough session length: all work and rest intervals plus ~1 min set-up per exercise. */
    fun estimateMinutes(items: List<Prescription>): Int =
        if (items.isEmpty()) 0 else ((items.sumOf { p -> intervals(p).drop(1).sumOf { it.seconds } } + items.size * 60) / 60.0).roundToInt().coerceAtLeast(5)

    /**
     * Ordered substitutes used when a planned exercise's equipment is missing. Each replacement trains the
     * same quality as closely as possible (e.g. max hangs → no-hang edge lifts → limit bouldering).
     */
    val SUBSTITUTES: Map<String, List<String>> = mapOf(
        "fb_max_hangs_10s" to listOf("fb_edge_lift_max", "climb_limit_bouldering"),
        "fb_repeaters_73" to listOf("fb_edge_lift_repeaters", "climb_arc"),
        "fb_density_hangs" to listOf("fb_edge_lift_repeaters", "climb_arc"),
        "climb_arc" to listOf("fb_repeaters_73", "fb_edge_lift_repeaters"),
        "climb_limit_bouldering" to listOf("fb_max_hangs_10s", "fb_edge_lift_max"),
        "climb_4x4" to listOf("climb_route_intervals", "fb_repeaters_73", "fb_edge_lift_repeaters"),
        "climb_route_intervals" to listOf("climb_4x4", "climb_arc", "fb_repeaters_73", "fb_edge_lift_repeaters"),
        "climb_campus_ladders" to listOf("climb_limit_bouldering", "pull_explosive", "pull_inverted_rows"),
        "climb_projecting" to listOf("climb_limit_bouldering", "fb_max_hangs_10s", "fb_edge_lift_max"),
        "pull_weighted_volume" to listOf("pull_bodyweight_tempo", "pull_band_rows", "pull_inverted_rows"),
        "pull_weighted_strength" to listOf("pull_bodyweight_tempo", "pull_band_rows", "pull_inverted_rows"),
        "pull_explosive" to listOf("pull_inverted_rows"),
        "push_ohp" to listOf("push_pike_pushups"),
        "push_dips" to listOf("push_deficit_pushups"),
        "core_front_lever_tuck" to listOf("core_hollow_body"),
        "core_front_lever_adv" to listOf("core_dragonflag", "core_hollow_body"),
        "core_front_lever_raises" to listOf("core_dragonflag", "core_pike_compression"),
        "core_dragonflag" to listOf("core_hollow_body"),
        "core_hanging_leg_raise" to listOf("core_pike_compression"),
        "mob_jefferson_curl" to listOf("mob_seated_pike"),
        "mob_band_external_rotation" to listOf("mob_sidelying_er"),
        "mob_scap_pullups" to listOf("mob_scap_pushups"),
    )

    /** First exercise in the substitution chain the athlete can do with their equipment, or null. */
    fun resolve(id: String, exercises: Map<String, Exercise>, equipment: Set<Equipment>): Exercise? =
        (listOf(id) + SUBSTITUTES[id].orEmpty()).firstNotNullOfOrNull { exercises[it]?.takeIf { e -> e.isAvailable(equipment) } }

    private val SHORT_LABEL = mapOf(
        ExerciseCategory.FINGERBOARD to "Fingers",
        ExerciseCategory.CLIMBING to "Climbing",
        ExerciseCategory.PULL to "Pull",
        ExerciseCategory.PUSH to "Antagonists",
        ExerciseCategory.CORE to "Core",
    )

    /** Session title derived from what is actually in it, so substitutions keep titles honest. */
    fun titleFor(items: List<Prescription>): String {
        val labels = items.mapNotNull { SHORT_LABEL[it.exercise.category] }.distinct()
        val hasMobility = items.any { it.exercise.category.isMobility }
        return when {
            labels.isEmpty() -> "Mobility & Flexibility"
            hasMobility && labels.size == 1 -> "${labels.single()} & Mobility"
            else -> labels.take(3).joinToString(" + ")
        }
    }

    private val ExerciseCategory.isMobility
        get() = this == ExerciseCategory.MOBILITY_LOWER || this == ExerciseCategory.MOBILITY_UPPER

    private class PhasePlan(
        val hang: String, val hang2: String, val pull: String, val climbA: String, val climbB: String,
        val coreA: String, val coreB: String, val pushA: String, val pushB: String,
    )

    private fun phasePlan(phase: Phase, goal: Goal): PhasePlan = when (phase) {
        Phase.BASE -> PhasePlan(
            "fb_repeaters_73", "fb_density_hangs", "pull_weighted_volume", "climb_arc",
            if (goal == Goal.SPORT_CLIMBING) "climb_route_intervals" else "climb_limit_bouldering",
            "core_front_lever_tuck", "core_hanging_leg_raise", "push_deficit_pushups", "push_dips",
        )
        Phase.MAX_STRENGTH -> PhasePlan(
            "fb_max_hangs_10s", "fb_max_hangs_10s", "pull_weighted_strength", "climb_limit_bouldering",
            if (goal == Goal.SPORT_CLIMBING) "climb_route_intervals" else "climb_limit_bouldering",
            "core_front_lever_adv", "core_dragonflag", "push_ohp", "push_dips",
        )
        Phase.POWER_ENDURANCE -> when (goal) {
            Goal.BOULDERING -> PhasePlan(
                "fb_max_hangs_10s", "fb_max_hangs_10s", "pull_explosive", "climb_campus_ladders",
                "climb_limit_bouldering", "core_front_lever_raises", "core_pike_compression", "push_ohp", "push_deficit_pushups",
            )
            Goal.SPORT_CLIMBING -> PhasePlan(
                "fb_repeaters_73", "fb_repeaters_73", "pull_weighted_volume", "climb_4x4",
                "climb_route_intervals", "core_front_lever_raises", "core_hanging_leg_raise", "push_deficit_pushups", "push_dips",
            )
            Goal.GENERAL_STRENGTH -> PhasePlan(
                "fb_max_hangs_10s", "fb_repeaters_73", "pull_weighted_strength", "climb_4x4",
                "climb_limit_bouldering", "core_front_lever_raises", "core_dragonflag", "push_ohp", "push_dips",
            )
        }
        Phase.PEAK -> PhasePlan(
            "fb_max_hangs_10s", "fb_max_hangs_10s", "pull_explosive", "climb_projecting", "climb_projecting",
            "core_hollow_body", "core_hanging_leg_raise", "push_deficit_pushups", "push_reverse_wrist_curl",
        )
    }

    private fun buildDays(
        phase: Phase,
        goal: Goal,
        weekInBlock: Int,
        weekStart: Long,
        baselineOn: (Long) -> StrengthBaseline,
        exercises: Map<String, Exercise>,
        equipment: Set<Equipment>,
        calibrate: Set<MaxTest> = emptySet(),
    ): List<TrainingDay> {
        val plan = phasePlan(phase, goal)
        val deload = weekInBlock == BLOCK_WEEKS
        var volume = if (deload) DELOAD_VOLUME else 1.0
        if (phase == Phase.PEAK) volume = minOf(volume, TAPER_VOLUME)
        // Linear intensity wave across the block; deload holds week-3 intensity.
        val intensityOffset = (minOf(weekInBlock, 3) - 2) * WEEKLY_INTENSITY_STEP

        fun rx(offset: Int, vararg ids: String): List<Prescription> {
            val baseline = baselineOn(weekStart + offset)
            return ids
                .mapNotNull { id -> resolve(id, exercises, equipment)?.let { ex -> ex to exercises[id]?.takeIf { it.id != ex.id } } }
                .distinctBy { it.first.id }
                .map { (ex, planned) -> prescribe(ex, planned, volume, intensityOffset, baseline, equipment) }
        }

        fun day(offset: Int, vararg ids: String): TrainingDay {
            val items = rx(offset, *ids)
            return TrainingDay(weekStart + offset, offset + 1, titleFor(items), items)
        }

        /** A test day with the [tests] the equipment allows, or [otherwise] when none is possible. */
        fun testDay(offset: Int, title: String, tests: List<MaxTest>, warmDown: List<String>, otherwise: () -> TrainingDay): TrainingDay {
            val items = rx(offset, *tests.map { it.testId }.toTypedArray())
            return if (items.isEmpty()) otherwise() else TrainingDay(weekStart + offset, offset + 1, title, items + rx(offset, *warmDown.toTypedArray()), isBenchmark = true)
        }

        val upperTests = MaxTest.UPPER_BODY.filter { !weekIsCalibration(calibrate) || it in calibrate }
        val fingerTests = MaxTest.FINGERS_AND_PULL.filter { !weekIsCalibration(calibrate) || it in calibrate }
        val rest = TrainingDay(weekStart + 6, 7, "Rest", emptyList(), isRest = true)

        val day1 = { day(0, plan.hang, plan.pull, plan.coreA, "mob_scap_pullups") }
        val day4 = { day(3, plan.hang2, plan.coreB, "core_hollow_body", "push_finger_extensions", "mob_wall_slides", "mob_ytw") }
        val day5 = { day(4, plan.climbB, plan.pushB, "mob_german_hang", "mob_doorway_pec") }
        return listOf(
            // Calibration week: anything never tested is measured first, and the loads follow from the next day.
            if (fingerTests.isNotEmpty() && weekIsCalibration(calibrate)) testDay(0, "Test Day – Fingers & Pull-up", fingerTests, listOf("mob_scap_pullups"), day1) else day1(),
            day(1, plan.climbA, plan.pushA, "push_reverse_wrist_curl", "mob_cossack_squat", "mob_90_90"),
            day(2, "mob_jefferson_curl", "mob_frog_stretch", "mob_middle_split", "mob_thoracic_rotation", "mob_band_external_rotation"),
            if (upperTests.isNotEmpty() && weekIsCalibration(calibrate)) testDay(3, "Test Day – Upper Body", upperTests, listOf("mob_doorway_pec"), day4) else day4(),
            // Deload week: low volume, so the maxes are tested fresh at its end for the next block.
            if (deload) testDay(4, "Test Day – Upper Body", MaxTest.UPPER_BODY, listOf("mob_doorway_pec"), day5) else day5(),
            day(5, "mob_couch_stretch", "mob_pancake", "core_pike_compression"),
            if (deload) testDay(6, "Test Day – Fingers & Pull-up", MaxTest.FINGERS_AND_PULL, emptyList()) { rest } else rest,
        )
    }

    private fun weekIsCalibration(calibrate: Set<MaxTest>) = calibrate.isNotEmpty()

    /** One exercise of a session: sets from the week's volume, load / reps / hold time from the athlete's maxes. */
    private fun prescribe(
        ex: Exercise,
        planned: Exercise?,
        volume: Double,
        intensityOffset: Double,
        baseline: StrengthBaseline,
        equipment: Set<Equipment>,
    ): Prescription {
        val sets = if (ex.category.isMobility || ex.category == ExerciseCategory.BENCHMARK) ex.sets
        else max(1, (ex.sets * volume).roundToInt())
        val share = ex.loadBasis in setOf(LoadBasis.PULLUP_REPS, LoadBasis.PUSHUP_REPS, LoadBasis.FL_HOLD)
        val intensity = ex.intensity?.let { (it + intensityOffset).coerceIn(if (share) 0.2 else 0.4, if (share) 0.9 else 0.95) }
        val max = baseline.max(MaxTest.forBasis(ex.loadBasis))
        var reps = ex.reps
        var workSec = ex.workSec
        if (max != null && intensity != null) {
            when (ex.loadBasis) {
                LoadBasis.PULLUP_REPS -> reps = (max * intensity).roundToInt().coerceIn(2, 12)
                LoadBasis.PUSHUP_REPS -> reps = (max * intensity).roundToInt().coerceIn(3, 30)
                LoadBasis.FL_HOLD -> workSec = (max * intensity).roundToInt().coerceIn(4, 20)
                else -> {}
            }
        }
        return Prescription(
            ex, sets, reps, workSec, ex.repRestSec, ex.setRestSec, intensity,
            loadText(ex, intensity, baseline, equipment), substitutedFor = planned?.name,
            load = shortLoad(ex, intensity, baseline, equipment),
        )
    }
}
