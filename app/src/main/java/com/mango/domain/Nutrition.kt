package com.mango.domain

import com.mango.data.Food
import com.mango.data.Nutrients
import com.mango.data.Recipe
import com.mango.data.RecipeIngredient
import com.mango.data.Sex
import com.mango.data.UserProfile

data class DailyTargets(
    val kcal: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val fiberG: Double,
    val vitaminCMg: Double,
    val omega3G: Double,
    val proteinPerKg: Double,
) {
    val proteinShare get() = proteinG * 4 / kcal
    val carbShare get() = carbsG * 4 / kcal
    val fatShare get() = fatG * 9 / kcal
}

/**
 * Energy and nutrient targets, all from peer-reviewed / clinical sources:
 *  - BMR: Mifflin MD, St Jeor ST et al. (1990) Am J Clin Nutr 51:241-247.
 *  - Protein 1.6-2.2 g/kg: Jäger R et al. (2017) ISSN Position Stand, JISSN 14:20;
 *    Morton RW et al. (2018) Br J Sports Med 52:376-384.
 *  - Fat 20-35 % energy, carbohydrate 3-5 g/kg for moderate training:
 *    Thomas DT, Erdman KA, Burke LM (2016) ACSM/AND/DC Joint Position, Med Sci Sports Exerc 48:543-568.
 *  - Fibre, vitamin C, ALA omega-3: Institute of Medicine Dietary Reference Intakes (2005 / 2000).
 */
object NutritionEngine {
    private const val FAT_SHARE_DEFAULT = 0.25
    private const val FAT_SHARE_MIN = 0.20
    private const val CARB_MIN_G_PER_KG = 3.0

    fun bmr(p: UserProfile): Double {
        val base = 10 * p.weightKg + 6.25 * p.heightCm - 5 * p.age
        return if (p.sex == Sex.MALE) base + 5 else base - 161
    }

    fun tdee(p: UserProfile): Double = bmr(p) * p.activityLevel.multiplier

    /** Protein scales with training-phase intensity across the ISSN 1.6-2.2 g/kg range. */
    fun proteinPerKg(phase: Phase): Double = when (phase) {
        Phase.BASE -> 1.6
        Phase.PEAK -> 1.8
        Phase.POWER_ENDURANCE -> 2.0
        Phase.MAX_STRENGTH -> 2.2
    }

    fun targets(p: UserProfile, phase: Phase): DailyTargets {
        val kcal = tdee(p)
        val ppk = proteinPerKg(phase)
        val protein = ppk * p.weightKg
        var fat = kcal * FAT_SHARE_DEFAULT / 9
        var carbs = (kcal - protein * 4 - fat * 9) / 4
        if (carbs < CARB_MIN_G_PER_KG * p.weightKg) {
            fat = kcal * FAT_SHARE_MIN / 9
            carbs = (kcal - protein * 4 - fat * 9) / 4
        }
        val male = p.sex == Sex.MALE
        return DailyTargets(
            kcal = kcal,
            proteinG = protein,
            carbsG = carbs.coerceAtLeast(0.0),
            fatG = fat,
            fiberG = if (male) 30.0 else 25.0,
            vitaminCMg = if (male) 90.0 else 75.0,
            omega3G = if (male) 1.6 else 1.1,
            proteinPerKg = ppk,
        )
    }

    fun ingredientNutrients(ingredient: RecipeIngredient, foods: Map<String, Food>): Nutrients {
        val f = foods[ingredient.food] ?: return Nutrients()
        return Nutrients(f.kcal, f.protein, f.carbs, f.fat, f.fiber, f.vitaminC, f.omega3) * (ingredient.grams / 100.0)
    }

    fun total(ingredients: List<RecipeIngredient>, foods: Map<String, Food>): Nutrients =
        ingredients.fold(Nutrients()) { acc, i -> acc + ingredientNutrients(i, foods) }

    fun withNutrients(recipe: Recipe, foods: Map<String, Food>): Recipe =
        recipe.copy(perServing = total(recipe.ingredients, foods) * (1.0 / recipe.servings.coerceAtLeast(1)))
}
