package com.mango.domain

import com.mango.data.Benchmark
import com.mango.data.LoadBasis
import com.mango.data.UserProfile
import kotlin.math.roundToInt

/**
 * The maxes the program is built on. Each one has a test exercise, done on test days (end of every deload week,
 * plus a calibration in the first week of a program for anything never tested), and drives the loads, reps or
 * hold times of the exercises that use it from the day after the test.
 */
enum class MaxTest(val testId: String, val label: String, val unit: String, val input: Input, val upperBody: Boolean) {
    FINGER("bench_max_hang_5s", "5 s max hang, 20 mm", "kg added", Input.ADDED_KG, false),
    PULL("bench_pullup_1rm", "Weighted pull-up 1RM", "kg added", Input.ADDED_KG, false),
    FRONT_LEVER("bench_front_lever_hold", "Tuck front lever hold", "s", Input.SECONDS, true),
    PULLUP_REPS("bench_pullup_max", "Max strict pull-ups", "reps", Input.REPS, true),
    DIP("bench_dip_rm", "Dips 1RM (estimated)", "kg added", Input.BODYWEIGHT_RM, true),
    PRESS("bench_press_rm", "Overhead press 1RM (estimated)", "kg", Input.WEIGHT_RM, true),
    PUSHUP_REPS("bench_pushup_max", "Max push-ups", "reps", Input.REPS, true),
    ;

    /** How the result is entered: a single number, or load + reps for a rep-max test. */
    enum class Input { ADDED_KG, REPS, SECONDS, BODYWEIGHT_RM, WEIGHT_RM }

    val key: String get() = name

    companion object {
        val UPPER_BODY: List<MaxTest> = entries.filter { it.upperBody }
        val FINGERS_AND_PULL: List<MaxTest> = entries.filter { !it.upperBody }

        fun forTest(exerciseId: String): MaxTest? = entries.firstOrNull { it.testId == exerciseId }

        fun forBasis(basis: LoadBasis): MaxTest? = when (basis) {
            LoadBasis.FINGER_1RM -> FINGER
            LoadBasis.PULL_1RM -> PULL
            LoadBasis.DIP_1RM -> DIP
            LoadBasis.PRESS_1RM -> PRESS
            LoadBasis.PULLUP_REPS -> PULLUP_REPS
            LoadBasis.PUSHUP_REPS -> PUSHUP_REPS
            LoadBasis.FL_HOLD -> FRONT_LEVER
            else -> null
        }
    }
}

object MaxTests {
    /**
     * Estimated 1RM from a rep-max set: Epley, 1RM ≈ load × (1 + reps / 30). Prediction equations are accurate
     * for sets of up to ~10 reps (LeSuer et al. 1997), so longer sets are capped there.
     */
    fun epley(load: Double, reps: Int): Double = if (reps <= 1) load else load * (1 + reps.coerceAtMost(10) / 30.0)

    /** Dips move the whole body: total load = bodyweight + added; the result is stored as added kg. */
    fun dipAdded1RM(addedKg: Double, reps: Int, bodyWeightKg: Double): Double =
        round05(epley(bodyWeightKg + addedKg, reps) - bodyWeightKg)

    fun press1RM(kg: Double, reps: Int): Double = round05(epley(kg, reps))

    private fun round05(v: Double) = (v * 2).roundToInt() / 2.0

    /** Latest result of each test recorded before [day] (a test day's results apply from the next day). */
    fun latest(benchmarks: List<Benchmark>, day: Long): Map<MaxTest, Double> =
        MaxTest.entries.mapNotNull { t ->
            benchmarks.filter { it.epochDay < day && t.key in it.results }.maxByOrNull { it.epochDay }
                ?.let { t to it.results.getValue(t.key) }
        }.toMap()

    /** The athlete's strength on [day]: tested maxes, else the profile's estimates for fingers and pull-ups. */
    fun baselineOn(profile: UserProfile, benchmarks: List<Benchmark>, day: Long): StrengthBaseline {
        val maxes = latest(benchmarks, day)
        val bw = benchmarks.filter { it.epochDay < day }.maxByOrNull { it.epochDay }?.bodyWeightKg ?: profile.weightKg
        return StrengthBaseline(
            fingerAddedKg = maxes[MaxTest.FINGER] ?: profile.fingerMaxAddedKg,
            pullAddedKg = maxes[MaxTest.PULL] ?: profile.pullUpMaxAddedKg,
            bodyWeightKg = bw,
            maxes = maxes,
        )
    }

    /** Tests with no result before the program started: the first week calibrates them. */
    fun untestedAt(benchmarks: List<Benchmark>, programStart: Long): Set<MaxTest> =
        MaxTest.entries.toSet() - latest(benchmarks, programStart).keys
}
