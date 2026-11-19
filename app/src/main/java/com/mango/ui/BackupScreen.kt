package com.mango.ui

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mango.MangoApp
import com.mango.data.BackupCodec
import com.mango.data.BackupSummary
import com.mango.data.Backups
import com.mango.data.MangoBackup
import com.mango.data.Repository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class BackupViewModel(repo: Repository, private val backups: Backups) : MangoViewModel(repo) {
    val status = backups.status
    private val _pending = MutableStateFlow<Pair<MangoBackup, BackupSummary>?>(null)
    /** A backup file that was read and is waiting for the user to confirm the restore. */
    val pending = _pending.asStateFlow()

    fun saveTo(uri: Uri) = launchSafe {
        backups.saveTo(uri)
        toast("Backup saved")
    }

    fun enableAutoSave(uri: Uri, name: String) = launchSafe {
        backups.enableAutoSave(uri, name)
        toast("Auto-save on – $name is kept up to date")
    }

    fun disableAutoSave() = backups.disableAutoSave()

    fun open(uri: Uri) = launchSafe {
        val b = backups.read(uri)
        _pending.value = b to BackupCodec.summary(b)
    }

    fun cancelRestore() {
        _pending.value = null
    }

    fun confirmRestore() = launchSafe {
        val b = _pending.value?.first ?: return@launchSafe
        _pending.value = null
        backups.restore(b)
        toast("Restored – profile, recipes and plan are back")
    }

    fun undoRestore() = launchSafe {
        backups.undoRestore()
        toast("Previous data put back")
    }
}

@Composable
fun BackupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val backups = (context.applicationContext as MangoApp).backups
    val vm = mangoViewModel { BackupViewModel(it, backups) }
    val status by vm.status.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmUndo by remember { mutableStateOf(false) }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }
    fun nameOf(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: "backup file"

    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupCodec.MIME)) { uri -> uri?.let(vm::saveTo) }
    val autoFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(BackupCodec.MIME)) { uri ->
        uri?.let { vm.enableAutoSave(it, nameOf(it)) }
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::open) }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp, 4.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BackHeader("Backup & restore", onBack)
            Text(
                "Everything stays on your phone. A backup is a small file you keep where you like – Downloads, a USB key, " +
                    "a synced folder – with your profile and options, your own and edited recipes, removed recipes, foods, pantry, " +
                    "benchmarks and meal plan.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionLabel("Auto-save", Modifier.padding(start = 4.dp, top = 4.dp))
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Keep a backup file up to date", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (status.autoFile == null) "Pick a file once – it's rewritten a few seconds after every change."
                            else status.autoFileName + if (status.lastSaved.isNotEmpty()) " · saved ${status.lastSaved}" else "",
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = status.autoFile != null,
                        onCheckedChange = { on -> if (on) autoFile.launch(Backups.suggestedFileName(auto = true)) else vm.disableAutoSave() },
                    )
                }
                status.error?.let { err ->
                    Text(err, fontSize = 13.sp, color = Danger)
                    TextButton(onClick = { autoFile.launch(Backups.suggestedFileName(auto = true)) }) { Text("Choose file again") }
                }
                if (status.autoFile != null && status.error == null) {
                    Text(
                        "The file stays after uninstalling Mango. On a new phone, use Restore and pick it.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionLabel("Manual", Modifier.padding(start = 4.dp, top = 4.dp))
            SectionCard {
                PrimaryButton("Save backup file…", { saveFile.launch(Backups.suggestedFileName(auto = false)) }, Modifier.fillMaxWidth(), icon = Icons.Filled.FileDownload)
                OutlinedButton(onClick = { openFile.launch(arrayOf(BackupCodec.MIME, "text/plain", "application/octet-stream")) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Restore from a backup file…")
                }
                Text(
                    "Restoring replaces your current profile, custom recipes, pantry and plan with the file's. You can undo it right after.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (status.canUndoRestore) {
                    TextButton(onClick = { confirmUndo = true }) { Text("Undo last restore") }
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
    }

    pending?.let { (_, s) ->
        AlertDialog(
            onDismissRequest = vm::cancelRestore,
            icon = { androidx.compose.material3.Icon(Icons.Filled.FileUpload, contentDescription = null) },
            title = { Text("Restore this backup?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (s.createdAt.isNotEmpty()) Text("Saved ${s.createdAt}", fontWeight = FontWeight.SemiBold)
                    Text(
                        listOfNotNull(
                            "profile & options".takeIf { s.hasProfile },
                            "${s.customRecipes} own recipes",
                            "${s.editedRecipes} edited recipes",
                            "${s.removedRecipes} removed recipes",
                            "${s.userFoods} own foods",
                            "${s.pantryItems} pantry items",
                            "${s.meals} planned meals",
                            "${s.benchmarks} benchmarks",
                        ).joinToString(" · "),
                        fontSize = 13.sp,
                    )
                    Text("Your current data is replaced (kept aside so you can undo).", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = vm::confirmRestore) { Text("Restore") } },
            dismissButton = { TextButton(onClick = vm::cancelRestore) { Text("Cancel") } },
        )
    }
    if (confirmUndo) {
        AlertDialog(
            onDismissRequest = { confirmUndo = false },
            title = { Text("Undo last restore?") },
            text = { Text("Puts back the data you had just before the last restore.") },
            confirmButton = { TextButton(onClick = { confirmUndo = false; vm.undoRestore() }) { Text("Undo restore") } },
            dismissButton = { TextButton(onClick = { confirmUndo = false }) { Text("Cancel") } },
        )
    }
}
