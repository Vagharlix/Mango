package com.mango.timer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.mango.MainActivity
import com.mango.R
import com.mango.domain.TimerInterval
import com.mango.domain.formatDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.ceil

data class TimerState(
    val title: String = "",
    val intervals: List<TimerInterval> = emptyList(),
    val index: Int = 0,
    val remainingMs: Long = 0,
    val running: Boolean = false,
    val active: Boolean = false,
) {
    val current: TimerInterval? get() = intervals.getOrNull(index)
    val next: TimerInterval? get() = intervals.getOrNull(index + 1)
    val finished: Boolean get() = active && index >= intervals.size
    val remainingSec: Int get() = ceil(remainingMs / 1000.0).toInt()
}

/**
 * Interval timer that keeps running with the screen locked: foreground service + partial wake lock,
 * monotonic [SystemClock.elapsedRealtime] timing, SoundPool 3-2-1 beeps and haptic transitions.
 */
class WorkoutTimerService : Service() {

    companion object {
        private const val CHANNEL_ID = "workout_timer"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_START = "com.mango.timer.START"
        private const val ACTION_PAUSE = "com.mango.timer.PAUSE"
        private const val ACTION_RESUME = "com.mango.timer.RESUME"
        private const val ACTION_STOP = "com.mango.timer.STOP"
        private const val ACTION_SKIP = "com.mango.timer.SKIP"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_INTERVALS = "intervals"

        private val _state = MutableStateFlow(TimerState())
        val state: StateFlow<TimerState> = _state.asStateFlow()

        fun start(context: Context, title: String, intervals: List<TimerInterval>) {
            val intent = Intent(context, WorkoutTimerService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_INTERVALS, Json.encodeToString(intervals))
            ContextCompat.startForegroundService(context, intent)
        }

        fun pause(context: Context) = send(context, ACTION_PAUSE)
        fun resume(context: Context) = send(context, ACTION_RESUME)
        fun stop(context: Context) = send(context, ACTION_STOP)
        fun skip(context: Context) = send(context, ACTION_SKIP)

        private fun send(context: Context, action: String) {
            if (!_state.value.active) return
            context.startService(Intent(context, WorkoutTimerService::class.java).setAction(action))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickJob: Job? = null
    private lateinit var soundPool: SoundPool
    private var beepShort = 0
    private var beepLong = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        soundPool = SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            .build()
        beepShort = soundPool.load(this, R.raw.beep_short, 1)
        beepLong = soundPool.load(this, R.raw.beep_long, 1)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Workout timer", NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val intervals = intent.getStringExtra(EXTRA_INTERVALS)
                    ?.let { runCatching { Json.decodeFromString<List<TimerInterval>>(it) }.getOrNull() }
                    .orEmpty()
                if (intervals.isEmpty()) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                _state.value = TimerState(
                    title = intent.getStringExtra(EXTRA_TITLE).orEmpty(),
                    intervals = intervals,
                    remainingMs = intervals.first().seconds * 1000L,
                    active = true,
                )
                goForeground()
                acquireWakeLock(intervals.sumOf { it.seconds } * 1000L + 10 * 60_000L)
                run()
            }
            ACTION_PAUSE -> {
                tickJob?.cancel()
                _state.update { it.copy(running = false) }
                notifyProgress()
            }
            ACTION_RESUME -> if (!_state.value.running && !_state.value.finished) run()
            ACTION_STOP -> shutdown()
            ACTION_SKIP -> if (!_state.value.finished) {
                val wasRunning = _state.value.running
                tickJob?.cancel()
                _state.update {
                    val next = it.index + 1
                    it.copy(index = next, remainingMs = (it.intervals.getOrNull(next)?.seconds ?: 0) * 1000L)
                }
                when {
                    _state.value.finished -> shutdown(keepState = true)
                    wasRunning -> run()
                    else -> notifyProgress()
                }
            }
            else -> if (!_state.value.active) stopSelf() // restarted by the system without a session
        }
        return START_NOT_STICKY
    }

    private fun run() {
        tickJob?.cancel()
        _state.update { it.copy(running = true) }
        tickJob = scope.launch {
            while (isActive && _state.value.index < _state.value.intervals.size) {
                val endAt = SystemClock.elapsedRealtime() + _state.value.remainingMs
                var lastSec = -1
                while (isActive) {
                    val left = endAt - SystemClock.elapsedRealtime()
                    if (left <= 0) break
                    _state.update { it.copy(remainingMs = left) }
                    val sec = ceil(left / 1000.0).toInt()
                    if (sec != lastSec) {
                        if (sec in 1..3) {
                            soundPool.play(beepShort, 1f, 1f, 1, 0, 1f)
                            vibrate(60)
                        }
                        notifyProgress()
                        lastSec = sec
                    }
                    delay(20)
                }
                if (!isActive) break
                soundPool.play(beepLong, 1f, 1f, 1, 0, 1f)
                vibrate(if (_state.value.next?.isWork == true) 400 else 250)
                _state.update {
                    val next = it.index + 1
                    it.copy(index = next, remainingMs = (it.intervals.getOrNull(next)?.seconds ?: 0) * 1000L)
                }
            }
            if (_state.value.finished) {
                _state.update { it.copy(running = false, remainingMs = 0) }
                notifyProgress()
                delay(1500)
                shutdown(keepState = true)
            }
        }
    }

    private fun vibrate(ms: Long) {
        if (vibrator.hasVibrator()) vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun goForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
    }

    private fun notifyProgress() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val s = _state.value
        val text = when {
            s.finished -> "Session complete"
            else -> "${s.current?.label ?: ""} · ${formatDuration(s.remainingSec)} · ${s.current?.detail ?: ""}"
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_TIMER, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fun action(label: String, action: String, code: Int) = NotificationCompat.Action(
            0, label,
            PendingIntent.getService(this, code, Intent(this, WorkoutTimerService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE),
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle(s.title.ifBlank { "Workout timer" })
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(!s.finished)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .apply {
                if (!s.finished) {
                    if (s.running) addAction(action("Pause", ACTION_PAUSE, 1)) else addAction(action("Resume", ACTION_RESUME, 2))
                    addAction(action("Stop", ACTION_STOP, 3))
                }
            }
            .build()
    }

    private fun acquireWakeLock(timeoutMs: Long) {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Mango:WorkoutTimer")
            .apply { acquire(timeoutMs) }
    }

    private fun shutdown(keepState: Boolean = false) {
        tickJob?.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        _state.update { if (keepState) it.copy(running = false) else TimerState() }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        soundPool.release()
        super.onDestroy()
    }
}
