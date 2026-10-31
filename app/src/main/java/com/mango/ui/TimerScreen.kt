package com.mango.ui

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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mango.domain.formatDuration
import com.mango.timer.WorkoutTimerService

@Composable
fun TimerScreen(onClose: () -> Unit) {
    val s by WorkoutTimerService.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(s.title.ifBlank { "Workout timer" }, style = MaterialTheme.typography.titleLarge)
        if (!s.active) {
            Spacer(Modifier.weight(1f))
            Text("No timer running. Start one from a timed exercise.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onClose) { Text("Back") }
            return@Column
        }

        val current = s.current
        val color = when {
            s.finished -> Leaf
            current?.isWork == true -> MangoOrange
            else -> Sky
        }
        Spacer(Modifier.weight(1f))
        Box(contentAlignment = Alignment.Center) {
            val total = (current?.seconds ?: 1) * 1000f
            CircularProgressIndicator(
                progress = { if (s.finished) 1f else (s.remainingMs / total).coerceIn(0f, 1f) },
                modifier = Modifier.size(280.dp),
                color = color,
                strokeWidth = 14.dp,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (s.finished) "DONE" else current?.label.orEmpty(), color = color, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (s.finished) "✓" else formatDuration(s.remainingSec),
                    fontSize = 84.sp, fontWeight = FontWeight.Black,
                )
                Text(current?.detail.orEmpty(), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        s.next?.let { Text("Next: ${it.label} ${formatDuration(it.seconds)} · ${it.detail}", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Spacer(Modifier.weight(1f))
        LinearProgressIndicator(
            progress = { if (s.intervals.isEmpty()) 0f else s.index.toFloat() / s.intervals.size },
            modifier = Modifier.fillMaxWidth(),
            color = color,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            if (s.finished) {
                Button(onClick = { WorkoutTimerService.stop(context); onClose() }, modifier = Modifier.weight(1f).height(64.dp)) { Text("Finish") }
            } else {
                Button(
                    onClick = { if (s.running) WorkoutTimerService.pause(context) else WorkoutTimerService.resume(context) },
                    modifier = Modifier.weight(1f).height(64.dp),
                ) { Text(if (s.running) "Pause" else "Resume", fontSize = 20.sp) }
                OutlinedButton(
                    onClick = { WorkoutTimerService.stop(context); onClose() },
                    modifier = Modifier.weight(1f).height(64.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Danger),
                ) { Text("Stop", fontSize = 20.sp) }
            }
        }
    }
}
