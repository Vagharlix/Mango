package com.mango.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mango.data.Food
import com.mango.data.OffProduct
import com.mango.data.PantryEntry
import com.mango.data.PantryItem
import com.mango.data.Repository
import com.mango.data.ScanResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn

class PantryViewModel(repo: Repository) : MangoViewModel(repo) {
    val today = repo.today()
    val entries = repo.pantry.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val foods = repo.foods.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _scan = MutableStateFlow<ScanResult?>(null)
    val scan = _scan.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy = _busy.asStateFlow()

    fun onBarcode(code: String) {
        if (_busy.value || _scan.value != null || code.isBlank() || !code.all { it.isDigit() }) return
        _busy.value = true
        launchSafe {
            try {
                _scan.value = repo.lookupBarcode(code)
            } finally {
                _busy.value = false
            }
        }
    }

    fun dismissScan() {
        _scan.value = null
    }

    /** Adds a scanned product and caches the barcode → food link for offline re-scans. */
    fun addScanned(barcode: String, productName: String, food: Food, grams: Double, days: Int, onDone: () -> Unit) = launchSafe {
        repo.linkBarcode(barcode, productName, food.id)
        repo.addToPantry(food.id, grams, today + days)
        _scan.value = null
        toast("Added ${food.name}")
        onDone()
    }

    fun addAsNewFood(product: OffProduct, grams: Double, days: Int, onDone: () -> Unit) = launchSafe {
        val food = repo.createFoodFromProduct(product)
        repo.addToPantry(food.id, grams, today + days)
        _scan.value = null
        toast("Added ${food.name}")
        onDone()
    }

    fun add(food: Food, grams: Double, days: Int) = launchSafe {
        repo.addToPantry(food.id, grams, today + days)
        toast("Added ${food.name}")
    }

    fun update(item: PantryItem) = launchSafe { repo.updatePantry(item) }
    fun delete(id: Long) = launchSafe { repo.deletePantry(id) }
}

@Composable
fun PantryScreen(onScan: () -> Unit) {
    val vm = mangoViewModel { PantryViewModel(it) }
    val entries by vm.entries.collectAsStateWithLifecycle()
    val foods by vm.foods.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<PantryEntry?>(null) }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Pantry", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = { adding = true }) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Text(" Add")
                    }
                }
                Text(
                    "Sorted by expiry. Items expiring soon are prioritised by the meal planner.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entries.isEmpty()) {
                item { Text("Your pantry is empty. Scan a barcode, add items manually, or tick items on the shopping list.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(entries, key = { it.item.id }) { e -> PantryRow(e, vm.today) { editing = e } }
        }
        ExtendedFloatingActionButton(
            onClick = onScan,
            icon = { Icon(Icons.Filled.QrCodeScanner, contentDescription = null) },
            text = { Text("Scan barcode") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    if (adding) {
        AddFoodDialog(foods, onDismiss = { adding = false }) { food, grams, days ->
            vm.add(food, grams, days)
            adding = false
        }
    }
    editing?.let { e ->
        EditPantryDialog(
            e,
            vm.today,
            onDismiss = { editing = null },
            onSave = { vm.update(it); editing = null },
            onDelete = { vm.delete(e.item.id); editing = null },
        )
    }
}

@Composable
private fun PantryRow(e: PantryEntry, today: Long, onClick: () -> Unit) {
    val days = e.item.expiresOnEpochDay - today
    val (label, color) = when {
        days < 0 -> "Expired" to Danger
        days == 0L -> "Expires today" to Danger
        days <= 3 -> "$days d left" to Warn
        else -> "$days d left" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    SectionCard(Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(e.food.name.replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.SemiBold)
                Text(quantityText(e.item.grams, e.food.unitGrams, e.food.unitName), style = MaterialTheme.typography.bodySmall)
            }
            Pill(label, color)
        }
    }
}

/** Searchable list over the local food catalog. */
@Composable
fun FoodSearch(foods: List<Food>, onPick: (Food) -> Unit, selected: Food? = null) {
    var query by remember { mutableStateOf("") }
    val matches = remember(query, foods) {
        val q = query.trim().lowercase()
        foods.filter { q.isEmpty() || q in it.name }.sortedBy { !it.name.startsWith(q) }.take(40)
    }
    Column {
        OutlinedTextField(query, { query = it }, label = { Text("Search foods") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        LazyColumn(Modifier.fillMaxWidth().height(200.dp)) {
            items(matches, key = { it.id }) { f ->
                Text(
                    f.name,
                    color = if (f.id == selected?.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    fontWeight = if (f.id == selected?.id) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.fillMaxWidth().clickable { onPick(f) }.padding(vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
fun AddFoodDialog(foods: List<Food>, onDismiss: () -> Unit, onAdd: (Food, Double, Int) -> Unit) {
    var food by remember { mutableStateOf<Food?>(null) }
    var grams by remember { mutableStateOf(100.0) }
    var days by remember { mutableStateOf(7.0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to pantry") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FoodSearch(foods, selected = food, onPick = {
                    food = it
                    grams = it.unitGrams ?: 100.0
                    days = it.shelfLifeDays.toDouble()
                })
                food?.let { Text("Selected: ${it.name}", color = MaterialTheme.colorScheme.primary) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Quantity", grams, { grams = it }, Modifier.weight(1f), suffix = "g")
                    NumberField("Expires in", days, { days = it }, Modifier.weight(1f), suffix = "days")
                }
            }
        },
        confirmButton = {
            TextButton(enabled = food != null && grams > 0, onClick = { food?.let { onAdd(it, grams, days.toInt()) } }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun EditPantryDialog(e: PantryEntry, today: Long, onDismiss: () -> Unit, onSave: (PantryItem) -> Unit, onDelete: () -> Unit) {
    var grams by remember { mutableStateOf(e.item.grams) }
    var days by remember { mutableStateOf((e.item.expiresOnEpochDay - today).toDouble()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(e.food.name.replaceFirstChar { it.uppercase() }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField("Quantity", grams, { grams = it }, suffix = "g")
                NumberField("Expires in", days, { days = it }, allowNegative = true, suffix = "days")
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(e.item.copy(grams = grams, expiresOnEpochDay = today + days.toLong())) }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("Delete", color = Danger) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
