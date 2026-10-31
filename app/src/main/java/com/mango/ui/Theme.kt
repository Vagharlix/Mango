package com.mango.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.mango.MangoApp
import com.mango.data.Nutrients
import com.mango.data.Repository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

// ---------- Theme: dark, high-contrast, large touch targets for chalky gym hands ----------

val MangoOrange = Color(0xFFFFA62B)
val Leaf = Color(0xFF7BD389)
val Sky = Color(0xFF6EC1FF)
val Warn = Color(0xFFFFC857)
val Danger = Color(0xFFFF6B6B)

private val MangoColors = darkColorScheme(
    primary = MangoOrange,
    onPrimary = Color.Black,
    primaryContainer = Color(0xFF3A2A12),
    onPrimaryContainer = MangoOrange,
    secondary = Leaf,
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF1E3326),
    onSecondaryContainer = Leaf,
    tertiary = Sky,
    background = Color(0xFF0E0F12),
    onBackground = Color(0xFFECEDEF),
    surface = Color(0xFF17191E),
    onSurface = Color(0xFFECEDEF),
    surfaceVariant = Color(0xFF22252C),
    onSurfaceVariant = Color(0xFFB4B8C0),
    surfaceContainer = Color(0xFF17191E),
    error = Danger,
    outline = Color(0xFF3A3E47),
)

@Composable
fun MangoTheme(content: @Composable () -> Unit) {
    val base = Typography()
    MaterialTheme(
        colorScheme = MangoColors,
        typography = base.copy(
            titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
            titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        ),
        content = content,
    )
}

// ---------- ViewModel plumbing (manual DI, no framework) ----------

abstract class MangoViewModel(protected val repo: Repository) : ViewModel() {
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun clearMessage() {
        _message.value = null
    }

    protected fun toast(text: String) {
        _message.value = text
    }

    /** Runs a repository action and surfaces failures as a snackbar instead of crashing. */
    protected fun launchSafe(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _message.value = e.message ?: "Something went wrong"
            }
        }
    }
}

@Composable
inline fun <reified VM : ViewModel> mangoViewModel(key: String? = null, crossinline create: (Repository) -> VM): VM {
    val repo = (LocalContext.current.applicationContext as MangoApp).repository
    return viewModel(key = key, factory = viewModelFactory { initializer { create(repo) } })
}

// ---------- Shared components & formatting ----------

fun Double.fmt(decimals: Int = 0): String =
    if (decimals == 0) roundToInt().toString() else "%.${decimals}f".format(this)

fun Long.asDate(pattern: String = "EEE d MMM"): String =
    LocalDate.ofEpochDay(this).format(DateTimeFormatter.ofPattern(pattern))

@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
fun Pill(text: String, color: Color = MaterialTheme.colorScheme.primary) {
    Box(
        Modifier
            .padding(end = 6.dp)
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun MacroLine(n: Nutrients, scale: Double = 1.0) {
    val s = n * scale
    Text(
        "${s.kcal.fmt()} kcal · P ${s.protein.fmt()} g · C ${s.carbs.fmt()} g · F ${s.fat.fmt()} g · Fibre ${s.fiber.fmt()} g",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun TargetBar(label: String, value: Double, target: Double, unit: String, color: Color = MangoOrange) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text("${value.fmt()} / ${target.fmt()} $unit", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LinearProgressIndicator(
            progress = { if (target > 0) (value / target).toFloat().coerceIn(0f, 1f) else 0f },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

/** Decimal text field that keeps its own text state and reports parsed values. */
@Composable
fun NumberField(
    label: String,
    value: Double,
    onValue: (Double) -> Unit,
    modifier: Modifier = Modifier,
    allowNegative: Boolean = false,
    suffix: String? = null,
) {
    fun format(v: Double) = if (v % 1.0 == 0.0) v.toInt().toString() else v.toString()
    var text by remember { mutableStateOf(format(value)) }
    // Sync external changes (e.g. data loaded from Room) without clobbering partial input like "70."
    LaunchedEffect(value) { if (text.toDoubleOrNull() != value) text = format(value) }
    OutlinedTextField(
        value = text,
        onValueChange = { new ->
            val cleaned = new.replace(',', '.')
            val pattern = if (allowNegative) Regex("^-?\\d*\\.?\\d*$") else Regex("^\\d*\\.?\\d*$")
            if (cleaned.matches(pattern)) {
                text = cleaned
                cleaned.toDoubleOrNull()?.let(onValue)
            }
        },
        label = { Text(label) },
        suffix = suffix?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}
