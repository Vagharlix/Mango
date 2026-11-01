package com.mango.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Saves and restores backups as JSON files the user owns (Downloads, a USB drive, a synced folder…), so
 * nothing depends on a server. With auto-save on, one chosen file is rewritten a few seconds after every
 * change; it survives uninstalling the app and can be restored on a new phone.
 */
class Backups(private val context: Context, private val repo: Repository) {
    data class Status(val autoFile: Uri? = null, val autoFileName: String = "", val lastSaved: String = "", val error: String? = null, val canUndoRestore: Boolean = false)

    private val prefs = context.getSharedPreferences("backups", Context.MODE_PRIVATE)
    private val undoFile = File(context.filesDir, "before-restore.json")
    private val writeLock = Mutex()
    private var lastWritten: String? = null

    private val _status = MutableStateFlow(readStatus())
    val status: StateFlow<Status> = _status.asStateFlow()

    private fun readStatus() = Status(
        autoFile = prefs.getString(KEY_URI, null)?.let(Uri::parse),
        autoFileName = prefs.getString(KEY_NAME, "").orEmpty(),
        lastSaved = prefs.getString(KEY_SAVED, "").orEmpty(),
        error = prefs.getString(KEY_ERROR, null),
        canUndoRestore = undoFile.exists(),
    )

    @OptIn(FlowPreview::class)
    fun startAutoSave(scope: CoroutineScope) {
        scope.launch {
            repo.backupChanges.debounce(3_000).collect {
                if (_status.value.autoFile != null) runCatching { autoSaveNow() }
            }
        }
    }

    /** Writes a backup to a file the user just picked ("Save backup file…"). */
    suspend fun saveTo(uri: Uri) = write(uri, BackupCodec.encode(repo.createBackup()))

    /** Turns auto-save on with a file created by the user, and writes it immediately. */
    suspend fun enableAutoSave(uri: Uri, displayName: String) {
        _status.value.autoFile?.takeIf { it != uri }?.let(::release)
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        prefs.edit().putString(KEY_URI, uri.toString()).putString(KEY_NAME, displayName).remove(KEY_ERROR).apply()
        lastWritten = null
        _status.value = readStatus()
        autoSaveNow()
    }

    fun disableAutoSave() {
        _status.value.autoFile?.let(::release)
        prefs.edit().remove(KEY_URI).remove(KEY_NAME).remove(KEY_SAVED).remove(KEY_ERROR).apply()
        _status.value = readStatus()
    }

    suspend fun autoSaveNow() {
        val uri = _status.value.autoFile ?: return
        val text = BackupCodec.encode(repo.createBackup())
        // Skip rewriting when nothing but the timestamp changed.
        val content = text.substringAfter("\"profile\"")
        if (content == lastWritten) return
        try {
            write(uri, text)
            lastWritten = content
            prefs.edit().putString(KEY_SAVED, now()).remove(KEY_ERROR).apply()
        } catch (e: Exception) {
            prefs.edit().putString(KEY_ERROR, "Couldn't write the auto-save file – choose it again.").apply()
        }
        _status.value = readStatus()
    }

    /** Reads and validates a backup file without changing anything (shown to the user before restoring). */
    suspend fun read(uri: Uri): MangoBackup = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: throw IllegalArgumentException("Couldn't open that file.")
        BackupCodec.decode(text)
    }

    /** Restores a backup; the current data is kept aside first so the restore can be undone. */
    suspend fun restore(backup: MangoBackup) {
        val current = BackupCodec.encode(repo.createBackup())
        withContext(Dispatchers.IO) { undoFile.writeText(current) }
        repo.restore(backup)
        _status.value = readStatus()
    }

    suspend fun undoRestore() {
        val text = withContext(Dispatchers.IO) { undoFile.takeIf { it.exists() }?.readText() } ?: return
        repo.restore(BackupCodec.decode(text))
        withContext(Dispatchers.IO) { undoFile.delete() }
        _status.value = readStatus()
    }

    private suspend fun write(uri: Uri, text: String) = writeLock.withLock {
        withContext(Dispatchers.IO) {
            // "wt" truncates: a shorter backup must not leave the tail of an older one behind.
            val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IllegalStateException("Couldn't open that file for writing.")
            out.bufferedWriter().use { it.write(text) }
        }
    }

    private fun release(uri: Uri) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    private fun now() = LocalDateTime.now().format(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm"))

    companion object {
        private const val KEY_URI = "auto_uri"
        private const val KEY_NAME = "auto_name"
        private const val KEY_SAVED = "auto_saved"
        private const val KEY_ERROR = "auto_error"

        fun suggestedFileName(auto: Boolean): String =
            if (auto) "mango-autosave.json" else "mango-backup-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + ".json"
    }
}
