package com.mango.domain

import com.mango.data.ActivityLevel
import com.mango.data.Benchmark
import com.mango.data.Equipment
import com.mango.data.Exercise
import com.mango.data.ExerciseCategory
import com.mango.data.Goal
import com.mango.data.ProgramMode
import com.mango.data.Sex
import com.mango.data.UserProfile
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

class MaxTestsTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val assets = listOf("src/main/assets", "app/src/main/assets").map(::File).first { it.exists() }
    private val exercises: Map<String, Exercise> =
        json.decodeFromString<List<Exercise>>(File(assets, "exercises.json").readText()).associateBy { it.id }
    private val start = LocalDate.of(2026, 11, 2).toEpochDay()
    private val profile = UserProfile(
        age = 30, heightCm = 180.0, weightKg = 70.0, sex = Sex.MALE, activityLevel = ActivityLevel.MODERATE,
        fingerMaxAddedKg = 20.0, pullUpMaxAddedKg = 20.0, goal = Goal.BOULDERING, programMode = ProgramMode.EVERGREEN,
        programStartEpochDay = start,
    )
    private val allTested = Benchmark(epochDay = start - 3, bodyWeightKg = 70.0, results = MaxTest.entries.associate { it.key to 10.0 })

    private fun weeks(benchmarks: List<Benchmark>, equipment: List<Equipment> = Equipment.DEFAULT) =
        Periodization.build(profile.copy(equipment = equipment), exercises, benchmarks, start)

    private fun ids(d: TrainingDay) = d.items.map { it.exercise.id }

    @Test fun everyTestHasItsExercise() {
        MaxTest.entries.forEach { t ->
            assertNotNull(t.testId, exercises[t.testId])
            assertEquals(ExerciseCategory.BENCHMARK, exercises.getValue(t.testId).category)
        }
        // Every loaded exercise follows a tested max.
        listOf("push_dips", "push_ohp", "push_deficit_pushups", "push_pike_pushups", "pull_bodyweight_tempo", "core_front_lever_tuck", "core_front_lever_adv")
            .forEach { assertNotNull(it, MaxTest.forBasis(exercises.getValue(it).loadBasis)) }
    }

    @Test fun repMaxSetsEstimateTheOneRepMax() {
        // Epley: 50 kg × 5 reps ≈ 58.3 kg; dips +10 kg × 5 at 70 kg bodyweight ≈ 93.3 kg total → +23.5 kg.
        assertEquals(58.5, MaxTests.press1RM(50.0, 5), 0.0)
        assertEquals(23.5, MaxTests.dipAdded1RM(10.0, 5, 70.0), 0.0)
        assertEquals(40.0, MaxTests.press1RM(40.0, 1), 0.0)
        // Long sets are capped at 10 reps (prediction equations hold up to ~10).
        assertEquals(MaxTests.press1RM(40.0, 10), MaxTests.press1RM(40.0, 25), 0.0)
    }

    @Test fun deloadWeeksEndWithTwoTestDays() {
        weeks(listOf(allTested)).filter { it.isDeload }.forEach { w ->
            assertEquals(
                listOf("bench_front_lever_hold", "bench_pullup_max", "bench_dip_rm", "bench_press_rm", "bench_pushup_max", "mob_doorway_pec"),
                ids(w.days[4]),
            )
            assertTrue(w.days[4].isBenchmark)
            assertEquals(listOf("bench_max_hang_5s", "bench_pullup_1rm"), ids(w.days[6]))
        }
        // Ordinary weeks have no tests.
        weeks(listOf(allTested)).filter { !it.isDeload }.forEach { w -> assertTrue(w.days.none { it.isBenchmark }) }
    }

    @Test fun firstWeekCalibratesWhatWasNeverTested() {
        val fresh = weeks(emptyList())[0]
        assertEquals(listOf("bench_max_hang_5s", "bench_pullup_1rm", "mob_scap_pullups"), ids(fresh.days[0]))
        assertTrue(fresh.days[3].isBenchmark)
        assertTrue("bench_dip_rm" in ids(fresh.days[3]))
        // Only the missing tests are calibrated; with everything tested, week 1 is a normal week.
        val onlyFingers = weeks(listOf(Benchmark(epochDay = start - 10, bodyWeightKg = 70.0, results = mapOf("FINGER" to 15.0, "PULL" to 25.0))))[0]
        assertFalse(onlyFingers.days[0].isBenchmark)
        assertTrue(onlyFingers.days[3].isBenchmark)
        assertTrue(weeks(listOf(allTested))[0].days.none { it.isBenchmark })
        // Recording the calibration doesn't make it disappear from week 1.
        val calibrated = weeks(listOf(Benchmark(epochDay = start, bodyWeightKg = 70.0, results = mapOf("FINGER" to 15.0))))[0]
        assertTrue(calibrated.days[0].isBenchmark)
    }

    @Test fun resultsDriveLoadsRepsAndHoldsFromTheNextDay() {
        val test = Benchmark(
            epochDay = start + 3, bodyWeightKg = 70.0,
            results = mapOf("DIP" to 20.0, "PUSHUP_REPS" to 30.0, "PULLUP_REPS" to 12.0, "FRONT_LEVER" to 20.0, "PRESS" to 50.0, "FINGER" to 20.0, "PULL" to 20.0),
        )
        val w = weeks(listOf(test))
        fun item(week: Int, day: Int, id: String) = w[week].days[day].items.first { it.exercise.id == id }
        // Before the test: no dip max yet → effort-based.
        assertTrue(item(0, 1, "push_deficit_pushups").reps == exercises.getValue("push_deficit_pushups").reps)
        // Week 2 (Base, mid-block intensity): push-ups 50 % of 30, dips 75 % of (70 + 20) − 70, tuck front lever 60 % of 20 s.
        assertEquals(15, item(1, 1, "push_deficit_pushups").reps)
        assertEquals("BW − 2.5 kg assist · 75%", item(1, 4, "push_dips").loadText)
        assertEquals(12, item(1, 0, "core_front_lever_tuck").workSec)
        // Max Strength block: overhead press at 72.5 % of the 50 kg 1RM in its first week → 36 kg.
        assertTrue(item(4, 1, "push_ohp").loadText, item(4, 1, "push_ohp").loadText.startsWith("≈ 36 kg"))
        // Without weights, tempo pull-ups take half the max reps.
        val noWeights = weeks(listOf(test), Equipment.DEFAULT - Equipment.WEIGHTS)
        assertEquals(6, noWeights[1].days[0].items.first { it.exercise.id == "pull_bodyweight_tempo" }.reps)
    }

    @Test fun thePressNeedsDumbbellsOrABarbell() {
        assertEquals(listOf(listOf(Equipment.DUMBBELLS)), exercises.getValue("push_ohp").requires)
        assertEquals(listOf(listOf(Equipment.DUMBBELLS)), exercises.getValue("bench_press_rm").requires)
        // Without them: pike push-ups replace the press and the press test is left out of test days.
        val noDumbbells = weeks(listOf(allTested), Equipment.DEFAULT - Equipment.DUMBBELLS)
        val press = noDumbbells[4].days[1].items.first { it.substitutedFor == "Overhead Press" }
        assertEquals("push_pike_pushups", press.exercise.id)
        assertFalse("bench_press_rm" in ids(noDumbbells[3].days[4]))
        assertTrue("bench_dip_rm" in ids(noDumbbells[3].days[4]))
    }

    @Test fun antagonistsAreInEveryWeek() {
        weeks(listOf(allTested)).take(16).filter { !it.isDeload }.forEach { w ->
            val all = w.days.flatMap { ids(it) }
            assertTrue(w.index.toString(), all.count { exercises.getValue(it).category == ExerciseCategory.PUSH } >= 3)
            assertTrue("push_finger_extensions" in all)
            assertTrue("mob_band_external_rotation" in all)
        }
    }

    @Test fun testSummariesReadAsAttempts() {
        val day7 = weeks(listOf(allTested))[3].days[6]
        assertEquals("Up to 5 attempts · rest 3:00", day7.items.first().summary)
        assertEquals("1 all-out set", weeks(listOf(allTested))[3].days[4].items.first { it.exercise.id == "bench_pushup_max" }.summary)
    }
}
