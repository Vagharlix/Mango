package com.mango.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mango.data.ExerciseCategory
import com.mango.domain.Phase

// Shared building blocks so every tab follows the same visual language:
// eyebrow + big title header, uppercase section labels, 16 dp cards, one orange primary action per screen.

val Pink = Color(0xFFFF8FAB)
val Faint = Color(0xFF6B707A)
val Hairline = Color(0xFF1F2228)

fun phaseColor(phase: Phase): Color = when (phase) {
    Phase.BASE -> Sky
    Phase.MAX_STRENGTH -> MangoOrange
    Phase.POWER_ENDURANCE -> Pink
    Phase.PEAK -> Leaf
}

fun categoryColor(category: ExerciseCategory): Color = when (category) {
    ExerciseCategory.FINGERBOARD -> MangoOrange
    ExerciseCategory.CLIMBING, ExerciseCategory.PULL -> Sky
    ExerciseCategory.PUSH -> Pink
    ExerciseCategory.CORE -> Leaf
    ExerciseCategory.BENCHMARK -> Warn
    ExerciseCategory.MOBILITY_LOWER, ExerciseCategory.MOBILITY_UPPER -> Color(0xFFB4B8C0)
}

@Composable
fun ScreenHeader(title: String, eyebrow: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            eyebrow?.let { Text(it, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text(title, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), content = actions)
    }
}

@Composable
fun RoundIconButton(icon: ImageVector, description: String, onClick: () -> Unit, tint: Color = MaterialTheme.colorScheme.onSurface) {
    OutlinedIconButton(
        onClick = onClick,
        modifier = Modifier.size(44.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) { Icon(icon, contentDescription = description, tint = tint) }
}

/** Back arrow row for pushed screens, with optional trailing context such as "1 of 4". */
@Composable
fun BackBar(onBack: () -> Unit, trailing: String? = null, icon: ImageVector = Icons.AutoMirrored.Filled.ArrowBack) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.padding(start = 0.dp)) { Icon(icon, contentDescription = "Back") }
        Spacer(Modifier.weight(1f))
        trailing?.let { Text(it, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Back arrow plus a large title, for list and editor screens. */
@Composable
fun BackHeader(title: String, onBack: () -> Unit) {
    Column {
        BackBar(onBack)
        Text(title, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 4.dp))
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text.uppercase(), modifier = modifier, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = color)
}

/** The one highlighted card of a screen (today's session, grocery day…). */
@Composable
fun HeroCard(
    label: String,
    title: String,
    subtitle: String? = null,
    labelColor: Color = MangoOrange,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(18.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionLabel(label, color = labelColor)
        Text(title, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 26.sp)
        subtitle?.let { Text(it, fontSize = 14.sp, color = Color(0xFFE8D9C2)) }
        content()
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = Icons.Filled.PlayArrow, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MangoOrange, contentColor = Color(0xFF111111)),
    ) {
        icon?.let {
            Icon(it, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
    }
}

/** Pinned bottom area holding a screen's main action. */
@Composable
fun BottomActionBar(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = Hairline)
        Column(Modifier.padding(16.dp, 12.dp, 16.dp, 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, highlight: Boolean = false) {
    Column(
        modifier
            .background(if (highlight) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = if (highlight) MangoOrange else MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = if (highlight) MangoOrange else MaterialTheme.colorScheme.onSurface, maxLines = 1)
    }
}

@Composable
fun NumberBadge(text: String, color: Color, size: Int = 36) {
    Box(
        Modifier.size(size.dp).background(color.copy(alpha = 0.16f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = color, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center) }
}

/** Small square-ish value tag, e.g. a load "+6.5 kg". */
@Composable
fun Tag(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, emphasized: Boolean = false) {
    Text(
        text,
        modifier = Modifier
            .background(if (emphasized) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        color = if (emphasized) MangoOrange else color,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
    )
}

/** Day tile used by the day strips (Meals): weekday, date, coloured dot. */
@Composable
fun DayTile(dow: String, num: String, selected: Boolean, dot: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier
            .height(64.dp)
            .background(if (selected) MangoOrange else MaterialTheme.colorScheme.surface, shape)
            .then(if (selected) Modifier else Modifier.border(1.dp, Color(0xFF2A2D34), shape))
            .clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val fg = if (selected) Color(0xFF111111) else MaterialTheme.colorScheme.onSurface
        Text(dow, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = fg.copy(alpha = 0.8f))
        Text(num, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold, color = fg)
        Box(Modifier.padding(top = 2.dp).size(6.dp).background(if (selected) fg else dot, CircleShape))
    }
}

/** A row that reveals more detail on tap: keeps secondary information out of the way. */
@Composable
fun ExpandableRow(title: String, trailing: String? = null, initiallyOpen: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable { mutableStateOf(initiallyOpen) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            trailing?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(end = 8.dp)) }
            Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null, tint = Faint)
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
        }
    }
}

/** Card grouping several [ExpandableRow]s separated by hairlines. */
@Composable
fun GroupedCard(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp)), content = content)
}
