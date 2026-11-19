package com.mango

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mango.data.ActivityLevel
import com.mango.data.Benchmark
import com.mango.data.Equipment
import com.mango.data.Goal
import com.mango.data.KitchenTool
import com.mango.data.MangoBackup
import com.mango.data.ProgramMode
import com.mango.data.Sex
import com.mango.data.UserProfile
import com.mango.data.WholeChickenMode
import com.mango.domain.WholeChicken
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import java.io.File

/**
 * Renders the real app screens with demo data for the README (docs/assets). Not part of the normal test run:
 * `./gradlew testDebugUnitTest --tests com.mango.ReadmeScreenshots -Pscreenshots` (see .github/workflows/screenshots.yml).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ReadmeScreenshots {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val out = File("build/readme-screenshots").apply { mkdirs() }
    private val app get() = ApplicationProvider.getApplicationContext<MangoApp>()

    /** 8 weeks into an evergreen program (deload week, test days ahead), two rounds of tests, whole chicken on. */
    private fun demo(today: Long): MangoBackup {
        val start = today - 50
        fun results(vararg r: Pair<String, Double>) = r.toMap()
        return MangoBackup(
            profile = UserProfile(
                age = 31, heightCm = 178.0, weightKg = 70.0, sex = Sex.MALE, activityLevel = ActivityLevel.MODERATE,
                fingerMaxAddedKg = 15.0, pullUpMaxAddedKg = 20.0, groceryDay = java.time.LocalDate.ofEpochDay(today).dayOfWeek.value,
                goal = Goal.BOULDERING, programMode = ProgramMode.EVERGREEN, programStartEpochDay = start,
                equipment = Equipment.DEFAULT, wholeChicken = WholeChickenMode.ALWAYS, chickenKg = 1.6,
                kitchen = listOf(KitchenTool.PRESSURE_COOKER, KitchenTool.AIR_FRYER),
            ),
            benchmarks = listOf(
                Benchmark(epochDay = start - 3, bodyWeightKg = 70.5, results = results(
                    "FINGER" to 15.0, "PULL" to 20.0, "DIP" to 12.0, "PRESS" to 40.0, "PUSHUP_REPS" to 28.0, "PULLUP_REPS" to 11.0, "FRONT_LEVER" to 14.0,
                )),
                Benchmark(epochDay = start + 25, bodyWeightKg = 70.0, results = results(
                    "FRONT_LEVER" to 17.0, "PULLUP_REPS" to 12.0, "DIP" to 15.0, "PRESS" to 42.5, "PUSHUP_REPS" to 32.0,
                )),
                Benchmark(epochDay = start + 27, bodyWeightKg = 70.0, results = results("FINGER" to 18.0, "PULL" to 22.5)),
            ),
        )
    }

    /** Every window on screen (the activity plus any dialog), from the framework's window list. */
    @Suppress("UNCHECKED_CAST")
    private fun windows(): List<View> {
        val global = Class.forName("android.view.WindowManagerGlobal")
        val instance = global.getMethod("getInstance").invoke(null)
        val views = global.getDeclaredField("mViews").apply { isAccessible = true }.get(instance) as List<View>
        return views.toList()
    }

    private fun shot(name: String) {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val base = compose.activity.window.decorView
        val bmp = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        base.draw(canvas)
        // Dialogs live in their own windows: dim the screen and draw them centred on top, as on a phone.
        windows().filter { it !== base && it.isShown && it.width > 0 }.forEach { dialog ->
            canvas.drawColor(Color.argb(150, 0, 0, 0))
            canvas.save()
            canvas.translate((base.width - dialog.width) / 2f, (base.height - dialog.height) / 2f)
            dialog.draw(canvas)
            canvas.restore()
        }
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    private fun tab(label: String) {
        compose.onNodeWithText(label).performClick()
        compose.waitForIdle()
    }

    /** Section labels are upper-cased on screen, so matching ignores case. */
    private fun waitForText(text: String) =
        compose.waitUntil(30_000) { compose.onAllNodes(hasText(text, substring = true, ignoreCase = true)).fetchSemanticsNodes().isNotEmpty() }

    @Test
    fun capture() {
        val repo = app.repository
        val today = repo.today()
        runBlocking { withTimeout(120_000) { repo.restore(demo(today)) } }

        // Train: this week, the plan strip and the maxes.
        waitForText("Your maxes")
        shot("train")

        // A test day of this deload week.
        compose.onAllNodes(hasScrollToNodeAction())[0].performScrollToNode(hasText("Test Day – Upper Body"))
        compose.onNodeWithText("Test Day – Upper Body").performClick()
        waitForText("Enter test results")
        shot("test-day")
        back()

        // Grocery: the menu, then the shopping list with the whole bird.
        tab("Grocery")
        waitForText("Menu · next 7 days")
        shot("grocery-menu")
        // The shopping list sits below the menu: lazy rows only exist once scrolled into view.
        compose.onAllNodes(hasScrollToNodeAction())[0].performScrollToNode(hasText("Whole chicken"))
        compose.onAllNodes(hasScrollToNodeAction())[0].performTouchInput { swipeUp(startY = bottom * 0.75f, endY = bottom * 0.4f, durationMillis = 1_500) } // slow: no fling
        compose.waitForIdle()
        shot("grocery")

        // Buy the bird: the kitchen prep card appears in Meals.
        runBlocking {
            val p = repo.profile.first()!!
            val bird = repo.shoppingList(repo.shoppingWindow(p), p).first().first { it.food.name == WholeChicken.WHOLE }
            repo.markBought(bird)
        }
        tab("Meals")
        waitForText("Kitchen prep today")
        shot("meals")

        // The pieces in the pantry: fridge for today and tomorrow, the rest frozen.
        tab("Pantry")
        waitForText("Frozen")
        shot("pantry")
        tab("Meals")

        // A classic from the recipe book.
        compose.onNodeWithContentDescription("Recipes").performClick()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performTextInput("Bourguignon")
        compose.waitForIdle()
        compose.onNodeWithText("Boeuf Bourguignon").performClick()
        // The "Based on" card is further down: the page top (title, servings, macros) is what matters.
        waitForText("Quantities for")
        shot("recipes")
        back()
        back()

        // Options › Kitchen.
        tab("Options")
        compose.onAllNodes(hasScrollToNodeAction())[0].performScrollToNode(hasText("Kitchen & whole chicken"))
        compose.onNodeWithText("Kitchen & whole chicken").performClick()
        waitForText("Appliances I have")
        shot("kitchen")
    }
}
