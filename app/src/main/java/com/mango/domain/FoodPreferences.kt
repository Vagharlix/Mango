package com.mango.domain

import com.mango.data.Food
import com.mango.data.Recipe

/**
 * Adapts recipes to the user's carb and protein choices and to "can't find it" replacements.
 * Carbs are swapped at equal energy (see [MealPlanner.effectiveIngredients]); proteins gram for gram.
 */
object FoodPreferences {
    /** Carbs that can stand in for each other as the starchy part of a dish. */
    val SWAPPABLE_CARBS = listOf("rice", "pasta", "potatoes", "quinoa", "noodles", "polenta", "barley", "buckwheat")

    /** Bread, wraps and oats shape the dish itself, so recipes using them are skipped rather than adapted. */
    val CARB_TYPES = SWAPPABLE_CARBS + listOf("bread & wraps", "oats")

    val PROTEIN_TYPES = listOf("chicken", "turkey", "beef", "pork", "fish", "seafood")

    private val CARB_FOOD = mapOf(
        "rice" to "brown rice", "pasta" to "whole wheat pasta", "potatoes" to "potato", "quinoa" to "quinoa",
        "noodles" to "rice noodles", "polenta" to "polenta", "barley" to "barley", "buckwheat" to "buckwheat groats",
    )

    /** Closest alternatives by cut and cooking method: mince for mince, whole cuts for whole cuts, oily fish for oily fish. */
    private val PROTEIN_SUBSTITUTES = mapOf(
        "chicken breast" to listOf("chicken thigh", "pork tenderloin", "beef sirloin"),
        "chicken thigh" to listOf("chicken breast", "pork tenderloin", "beef sirloin"),
        "pork tenderloin" to listOf("chicken breast", "chicken thigh", "beef sirloin"),
        "beef sirloin" to listOf("pork tenderloin", "chicken thigh", "chicken breast"),
        "lean beef mince" to listOf("turkey mince"),
        "turkey mince" to listOf("lean beef mince"),
        "salmon fillet" to listOf("mackerel fillet", "cod fillet"),
        "mackerel fillet" to listOf("salmon fillet", "cod fillet"),
        "cod fillet" to listOf("salmon fillet", "mackerel fillet", "shrimp"),
        "shrimp" to listOf("cod fillet", "chicken breast"),
        "canned tuna in water" to listOf("sardines canned in oil"),
        "sardines canned in oil" to listOf("canned tuna in water"),
        "chicken broth" to listOf("vegetable broth"),
        "veal" to listOf("chicken breast", "pork tenderloin", "chicken thigh"),
        "stewing beef" to listOf("chicken thigh", "pork tenderloin"),
        "cooked ham" to listOf("chicken breast"),
        "clams" to listOf("shrimp"),
        // Lardons and prosciutto define their dishes (quiche lorraine, saltimbocca): skipped rather than swapped.
    )

    /**
     * Substitutions (recipe food → food used) adapting [recipe], or null when it can't be adapted sensibly
     * (it is then skipped by the planner). Replacement targets are never disliked foods.
     */
    fun swaps(
        recipe: Recipe,
        carbOff: Collection<String>,
        proteinOff: Collection<String>,
        manual: Map<String, String>,
        dislikes: List<String>,
        foods: Map<String, Food>,
    ): Map<String, String>? {
        fun ok(name: String): Boolean {
            val f = foods[name] ?: return false
            return (f.carbType == null || f.carbType !in carbOff) &&
                (f.proteinType == null || f.proteinType !in proteinOff) &&
                dislikes.none { MealPlanner.matchesDislike(f, name, it) }
        }
        val out = mutableMapOf<String, String>()
        for (ing in recipe.ingredients) {
            val food = foods[ing.food] ?: continue
            val replacement = manual[ing.food]?.takeIf { it in foods }
            if (replacement != null) {
                out[ing.food] = replacement
                continue
            }
            val carb = food.carbType
            if (carb != null && carb in carbOff) {
                if (carb !in SWAPPABLE_CARBS) return null
                out[ing.food] = SWAPPABLE_CARBS.mapNotNull { CARB_FOOD[it] }.firstOrNull(::ok) ?: return null
                continue
            }
            val protein = food.proteinType
            if (protein != null && protein in proteinOff) {
                out[ing.food] = PROTEIN_SUBSTITUTES[ing.food].orEmpty().firstOrNull(::ok) ?: return null
            }
        }
        return out
    }

    /** Similar items offered when [food] can't be found in the shop. */
    fun replacementsFor(food: Food, foods: Collection<Food>): List<Food> {
        val family = food.family ?: food.swapGroup ?: return emptyList()
        return foods.filter { it.name != food.name && (it.family == family || it.swapGroup == family) }.sortedBy { it.name }
    }
}
