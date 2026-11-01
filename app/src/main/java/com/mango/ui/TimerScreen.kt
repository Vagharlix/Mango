package com.mango.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mango.domain.formatDuration
import com.mango.timer.WorkoutTimerService

/**
 * Full-screen timer, deliberately separate from exercise information: phase name above the ring,
 * only the countdown inside it, set progress below, cues folded away.
 */
@Composable
fun TimerScreen(onClose: () -> Unit) {
    val s by WorkoutTimerService.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val current = s.current
    val color = when {
        s.finished -> Leaf
        current?.isWork == true -> MangoOrange
        else -> Sky
    }

    Column(Modifier.fillMaxSize().padding(20.dp, 8.dp, 20.dp, 20.dp)) {
        // Top bar: what is being trained.
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "Close timer") }
            Column(Modifier.weight(1f)) {
                Text(current?.exercise?.ifBlank { null } ?: s.title.ifBlank { "Workout timer" }, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                if (s.active && s.title.isNotBlank() && current?.exercise != s.title) {
                    Text(s.title, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }

        if (!s.active) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No timer running. Start one from an exercise or a session.", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            OutlinedButton(onClick = onClose, modifier = Modifier.fillMaxWidth().height(54.dp)) { Text("Back") }
            return@Column
        }

        // Set progress.
        if (current != null && current.sets > 0 && !s.finished) {
            Column(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Set ${current.set.coerceAtMost(current.sets)} of ${current.sets}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    repeat(current.sets) { i ->
                        val n = i + 1
                        val c = when {
                            n < current.set -> Leaf
                            n == current.set -> color
                            else -> Color(0xFF2A2D34)
                        }
                        Box(Modifier.size(width = if (current.sets > 6) 20.dp else 32.dp, height = 8.dp).background(c, RoundedCornerShape(4.dp)))
                    }
                }
            }
        }

        // Phase + countdown.
        Column(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                if (s.finished) "DONE" else current?.label.orEmpty(),
                color = color, fontSize = 34.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp,
            )
            Spacer(Modifier.height(18.dp))
            Box(contentAlignment = Alignment.Center) {
                val total = (current?.seconds ?: 1) * 1000f
                CircularProgressIndicator(
                    progress = { if (s.finished) 1f else (s.remainingMs / total).coerceIn(0f, 1f) },
                    modifier = Modifier.size(260.dp),
                    color = color,
                    strokeWidth = 14.dp,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                Text(
                    if (s.finished) "✓" else formatDuration(s.remainingSec).removeSuffix("s"),
                    fontSize = if (s.remainingSec >= 60) 72.sp else 110.sp,
                    fontWeight = FontWeight.Black,
                )
            }
            Spacer(Modifier.height(18.dp))
            s.next?.let { next ->
                Text(
                    "Next · ${next.label.lowercase().replaceFirstChar { it.uppercase() }} ${formatDuration(next.seconds)}",
                    fontSize = 16.sp,
                    color = if (next.isWork) MangoOrange else Sky,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            current?.detail?.takeIf { !s.finished && it.isNotBlank() }?.let {
                Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        }

        // Cues folded away, available when needed.
        val cues = current?.cues.orEmpty()
        if (cues.isNotEmpty() && !s.finished) {
            var open by remember { mutableStateOf(false) }
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                    .clickable { open = !open }
                    .padding(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Cues · ${cues.first()}", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1)
                    Icon(if (open) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess, contentDescription = null, tint = Faint)
                }
                AnimatedVisibility(open) {
                    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        cues.forEach { Text("• $it", fontSize = 14.sp) }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        // Controls.
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            if (s.finished) {
                Button(
                    onClick = {
                        WorkoutTimerService.stop(context)
                        onClose()
                    },
                    modifier = Modifier.weight(1f).height(68.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Leaf, contentColor = Color(0xFF111111)),
                ) { Text("Finish", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold) }
            } else {
                Button(
                    onClick = { if (s.running) WorkoutTimerService.pause(context) else WorkoutTimerService.resume(context) },
                    modifier = Modifier.weight(1f).height(68.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MangoOrange, contentColor = Color(0xFF111111)),
                ) {
                    Icon(if (s.running) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (s.running) "Pause" else "Resume", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                }
                OutlinedButton(
                    onClick = { WorkoutTimerService.skip(context) },
                    modifier = Modifier.size(68.dp),
                    shape = RoundedCornerShape(18.dp),
                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                ) { Icon(Icons.Filled.SkipNext, contentDescription = "Skip to next interval") }
                OutlinedButton(
                    onClick = {
                        WorkoutTimerService.stop(context)
                        onClose()
                    },
                    modifier = Modifier.size(68.dp),
                    shape = RoundedCornerShape(18.dp),
                    border = BorderStroke(1.dp, Color(0xFF5A2A2A)),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger),
                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                ) { Icon(Icons.Filled.Stop, contentDescription = "Stop timer") }
            }
        }
    }
}
