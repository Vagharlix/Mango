package com.mango.data

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

enum class Sex { MALE, FEMALE }

/** Physical-activity multipliers applied to Mifflin-St Jeor BMR (McArdle, Katch & Katch, Exercise Physiology). */
enum class ActivityLevel(val multiplier: Double, val label: String) {
    SEDENTARY(1.2, "Sedentary – desk job, little exercise"),
    LIGHT(1.375, "Light – 1-3 sessions / week"),
    MODERATE(1.55, "Moderate – 3-5 sessions / week"),
    VERY_ACTIVE(1.725, "Very active – 6-7 sessions / week"),
    ATHLETE(1.9, "Athlete – double sessions or physical job"),
}

enum class Goal(val label: String) {
    BOULDERING("Bouldering"),
    SPORT_CLIMBING("Sport Climbing"),
    GENERAL_STRENGTH("General Strength"),
}

enum class ProgramMode { FIXED, EVERGREEN }

enum class MealType(val label: String, val energyShare: Double) {
    BREAKFAST("Breakfast", 0.25),
    LUNCH("Lunch", 0.30),
    DINNER("Dinner", 0.30),
    SNACK("Snack", 0.15),
}

enum class MealStatus { PLANNED, COOKED, SKIPPED }

enum class FoodCategory { PRODUCE, PROTEIN, DAIRY, GRAIN, LEGUME, NUT_SEED, FAT_OIL, PANTRY, SPICE, OTHER }

enum class ExerciseCategory(val label: String) {
    FINGERBOARD("Fingerboard"),
    CLIMBING("Climbing"),
    PULL("Pull"),
    PUSH("Push / Antagonist"),
    CORE("Core"),
    MOBILITY_LOWER("Lower-Body Mobility"),
    MOBILITY_UPPER("Shoulder Health"),
    BENCHMARK("Benchmark"),
}

enum class Equipment(val label: String) {
    FINGERBOARD("Hangboard / fingerboard"),
    LIFTING_EDGE("No-hang lifting edge / block"),
    BOULDER_WALL("Bouldering wall / board"),
    ROPE_WALL("Rope / lead wall"),
    CAMPUS_BOARD("Campus board"),
    PULL_UP_BAR("Pull-up bar / hangboard jugs"),
    RINGS("Gymnastic rings"),
    DIP_BARS("Dip bars / parallettes"),
    WEIGHTS("Free weights / weight belt"),
    BENCH("Flat bench"),
    RESISTANCE_BAND("Resistance bands"),
    ;

    companion object {
        /** A typical climbing gym: everything except the niche no-hang edge. */
        val DEFAULT: List<Equipment> = entries - LIFTING_EDGE
    }
}

/** How a prescription's load is derived. 1RM bases are percentages of total system load (bodyweight + added). */
enum class LoadBasis { FINGER_1RM, PULL_1RM, BODYWEIGHT, RPE, NONE }

@Entity(tableName = "user_profile")
data class UserProfile(
    @PrimaryKey val id: Int = 1,
    val age: Int = 30,
    val heightCm: Double = 175.0,
    val weightKg: Double = 70.0,
    val sex: Sex = Sex.MALE,
    val activityLevel: ActivityLevel = ActivityLevel.MODERATE,
    /** Added load (kg) at 1RM on a 20 mm edge; negative = assisted. */
    val fingerMaxAddedKg: Double = 10.0,
    /** Added load (kg) at weighted pull-up 1RM; negative = assisted. */
    val pullUpMaxAddedKg: Double = 20.0,
    /** ISO day of week, 1 = Monday … 7 = Sunday. */
    val groceryDay: Int = 7,
    val goal: Goal = Goal.BOULDERING,
    val dislikedIngredients: List<String> = emptyList(),
    val programMode: ProgramMode = ProgramMode.EVERGREEN,
    val programWeeks: Int = 12,
    val programStartEpochDay: Long = 0,
    val southernHemisphere: Boolean = false,
    /** Training equipment the athlete can access right now; the schedule adapts whenever it changes. */
    val equipment: List<Equipment> = Equipment.DEFAULT,
    /** Number of different breakfasts rotated through a week. */
    val breakfastVariety: Int = 2,
    /** Spice-rack staples the user does NOT keep stocked (they then appear on shopping lists). */
    val stapleOptOut: List<String> = emptyList(),
)

/** Nutrients per 100 g. Values from USDA FoodData Central. */
@Serializable
@Entity(tableName = "foods", indices = [Index(value = ["name"], unique = true)])
data class Food(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val category: FoodCategory,
    val kcal: Double,
    val protein: Double,
    val carbs: Double,
    val fat: Double,
    val fiber: Double = 0.0,
    val vitaminC: Double = 0.0,
    val omega3: Double = 0.0,
    val shelfLifeDays: Int = 7,
    /** Allergen / dislike groups, e.g. "pork", "dairy", "nuts", "gluten". */
    val groups: List<String> = emptyList(),
    /** Months (1-12, northern hemisphere) when this produce is in season; empty = not seasonal. */
    val seasonMonths: List<Int> = emptyList(),
    val unitGrams: Double? = null,
    val unitName: String? = null,
    val isUserAdded: Boolean = false,
    /** Interchangeable-ingredient group (e.g. "beans"): one member is used for the whole week. */
    val swapGroup: String? = null,
    /** Spice or long-life staple kept in the spice rack rather than bought per week. */
    val isStaple: Boolean = false,
)

/** Local barcode cache: maps a scanned product to a food in the catalog (generic or created from Open Food Facts). */
@Entity(tableName = "barcode_products")
data class BarcodeProduct(
    @PrimaryKey val barcode: String,
    val productName: String,
    val foodId: Long,
)

@Entity(tableName = "pantry", indices = [Index("foodId")])
data class PantryItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val foodId: Long,
    val grams: Double,
    val expiresOnEpochDay: Long,
    val addedOnEpochDay: Long,
)

@Serializable
data class RecipeIngredient(val food: String, val grams: Double)

@Serializable
data class Nutrients(
    val kcal: Double = 0.0,
    val protein: Double = 0.0,
    val carbs: Double = 0.0,
    val fat: Double = 0.0,
    val fiber: Double = 0.0,
    val vitaminC: Double = 0.0,
    val omega3: Double = 0.0,
) {
    operator fun plus(o: Nutrients) = Nutrients(
        kcal + o.kcal, protein + o.protein, carbs + o.carbs, fat + o.fat,
        fiber + o.fiber, vitaminC + o.vitaminC, omega3 + o.omega3,
    )

    operator fun times(k: Double) = Nutrients(
        kcal * k, protein * k, carbs * k, fat * k, fiber * k, vitaminC * k, omega3 * k,
    )
}

@Serializable
@Entity(tableName = "recipes")
data class Recipe(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val mealType: MealType,
    val cuisine: String = "",
    val servings: Int = 1,
    val prepMinutes: Int = 20,
    val ingredients: List<RecipeIngredient> = emptyList(),
    val steps: List<String> = emptyList(),
    /** Cached per-serving nutrients, recomputed from [ingredients] on seed and on every edit. */
    @Embedded(prefix = "ps_") val perServing: Nutrients = Nutrients(),
    val isCustom: Boolean = false,
    val isUserModified: Boolean = false,
    val isHidden: Boolean = false,
    /** How many main meals one cooking session can cover (1 = cook fresh each time). */
    val batchMeals: Int = 1,
)

/** One planned meal. Composite key keeps exactly one slot per day and meal type. */
@Entity(tableName = "meal_slots", primaryKeys = ["epochDay", "mealType"])
data class MealSlot(
    val epochDay: Long,
    val mealType: MealType,
    val recipeId: Long,
    val portions: Double,
    val status: MealStatus = MealStatus.PLANNED,
    /** Ingredient substitutions for this meal (recipe food → food actually used), see MealPlanner.consolidate. */
    val swaps: Map<String, String> = emptyMap(),
    /** Shared by all meals of one batch-cooking session; 0 = not batch cooked. */
    val batchId: Long = 0,
    /** True for the meals eaten from a batch cooked earlier (no cooking, no extra pantry deduction). */
    val isLeftover: Boolean = false,
)

@Serializable
@Entity(tableName = "exercises")
data class Exercise(
    @PrimaryKey val id: String,
    val name: String,
    val category: ExerciseCategory,
    val description: String,
    val cues: List<String> = emptyList(),
    val sets: Int,
    val reps: Int,
    val workSec: Int = 0,
    val repRestSec: Int = 0,
    val setRestSec: Int = 0,
    /** Fraction of total-system 1RM (bodyweight + added load). */
    val intensity: Double? = null,
    val loadBasis: LoadBasis = LoadBasis.NONE,
    /** Equipment needed: every group must be satisfied by at least one item (e.g. [[BOULDER_WALL, ROPE_WALL]]). */
    val requires: List<List<Equipment>> = emptyList(),
    val source: String = "",
) {
    fun isAvailable(equipment: Collection<Equipment>): Boolean = requires.all { group -> group.any { it in equipment } }
}

@Entity(tableName = "benchmarks")
data class Benchmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val epochDay: Long,
    val fingerMaxAddedKg: Double,
    val pullUpMaxAddedKg: Double,
    val bodyWeightKg: Double,
)
