package com.mango.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Kitchen
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

object Routes {
    const val TRAINING = "training"
    const val MEALS = "meals"
    const val GROCERY = "grocery"
    const val PANTRY = "pantry"
    const val PROFILE = "profile"
    const val TIMER = "timer"
    const val SCANNER = "scanner"
    const val RECIPES = "recipes"
    const val RECIPE = "recipe/{id}?portions={portions}"
    const val EDITOR = "editor/{id}"
    const val SESSION = "session/{week}/{day}"
    const val EXERCISE = "exercise/{week}/{day}/{index}"
    const val PLAN = "plan"
    fun recipe(id: Long, portions: Double? = null) = "recipe/$id" + (portions?.let { "?portions=$it" } ?: "")
    fun session(week: Int, day: Int) = "session/$week/$day"
    fun exercise(week: Int, day: Int, index: Int) = "exercise/$week/$day/$index"
    fun editor(id: Long) = "editor/$id"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.TRAINING, "Train", Icons.Filled.FitnessCenter),
    Tab(Routes.MEALS, "Meals", Icons.Filled.Restaurant),
    Tab(Routes.GROCERY, "Grocery", Icons.Filled.ShoppingCart),
    Tab(Routes.PANTRY, "Pantry", Icons.Filled.Kitchen),
    Tab(Routes.PROFILE, "Profile", Icons.Filled.Person),
)

@Composable
fun MangoNavHost(openTimer: Boolean, onTimerOpened: () -> Unit) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route

    LaunchedEffect(openTimer) {
        if (openTimer) {
            nav.navigate(Routes.TIMER) { launchSingleTop = true }
            onTimerOpened()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (tabs.any { it.route == route }) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = { nav.switchTab(tab.route) },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.primaryContainer),
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Routes.TRAINING, modifier = Modifier.padding(padding)) {
            composable(Routes.TRAINING) {
                TrainingScreen(
                    onOpenSession = { week, day -> nav.navigate(Routes.session(week, day)) },
                    onOpenPlan = { nav.navigate(Routes.PLAN) },
                    onSetupProfile = { nav.switchTab(Routes.PROFILE) },
                )
            }
            composable(
                Routes.SESSION,
                arguments = listOf(navArgument("week") { type = NavType.IntType }, navArgument("day") { type = NavType.IntType }),
            ) { entry ->
                val week = entry.arguments?.getInt("week") ?: 0
                val day = entry.arguments?.getInt("day") ?: 0
                SessionScreen(
                    week = week,
                    day = day,
                    onOpenExercise = { nav.navigate(Routes.exercise(week, day, it)) },
                    onOpenTimer = { nav.navigate(Routes.TIMER) { launchSingleTop = true } },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(
                Routes.EXERCISE,
                arguments = listOf(
                    navArgument("week") { type = NavType.IntType },
                    navArgument("day") { type = NavType.IntType },
                    navArgument("index") { type = NavType.IntType },
                ),
            ) { entry ->
                ExerciseScreen(
                    week = entry.arguments?.getInt("week") ?: 0,
                    day = entry.arguments?.getInt("day") ?: 0,
                    index = entry.arguments?.getInt("index") ?: 0,
                    onOpenTimer = { nav.navigate(Routes.TIMER) { launchSingleTop = true } },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.PLAN) {
                PlanScreen(onOpenSession = { week, day -> nav.navigate(Routes.session(week, day)) }, onBack = { nav.popBackStack() })
            }
            composable(Routes.MEALS) {
                MealsScreen(
                    onOpenRecipe = { id, portions -> nav.navigate(Routes.recipe(id, portions)) },
                    onOpenRecipes = { nav.navigate(Routes.RECIPES) },
                    onOpenGrocery = { nav.switchTab(Routes.GROCERY) },
                    onSetupProfile = { nav.switchTab(Routes.PROFILE) },
                )
            }
            composable(Routes.GROCERY) { GroceryScreen(onOpenRecipe = { id, portions -> nav.navigate(Routes.recipe(id, portions)) }) }
            composable(Routes.PANTRY) { PantryScreen(onScan = { nav.navigate(Routes.SCANNER) }) }
            composable(Routes.PROFILE) { ProfileScreen() }
            composable(Routes.TIMER) { TimerScreen(onClose = { nav.popBackStack() }) }
            composable(Routes.SCANNER) { ScannerScreen(onDone = { nav.popBackStack() }) }
            composable(Routes.RECIPES) {
                RecipesScreen(
                    onOpen = { nav.navigate(Routes.recipe(it)) },
                    onCreate = { nav.navigate(Routes.editor(0)) },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(
                Routes.RECIPE,
                arguments = listOf(
                    navArgument("id") { type = NavType.LongType },
                    navArgument("portions") { type = NavType.FloatType; defaultValue = 1f },
                ),
            ) { entry ->
                RecipeDetailScreen(
                    id = entry.arguments?.getLong("id") ?: 0,
                    initialServings = (entry.arguments?.getFloat("portions") ?: 1f).toDouble(),
                    onEdit = { nav.navigate(Routes.editor(it)) },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.EDITOR, arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                RecipeEditorScreen(id = entry.arguments?.getLong("id") ?: 0, onDone = { nav.popBackStack() })
            }
        }
    }
}

private fun NavHostController.switchTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
