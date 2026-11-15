package com.mango.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mango.domain.KitchenPrep

/** Today's kitchen jobs: break down the bird, make stock, thaw for tomorrow, freeze before it goes off. */
@Composable
fun KitchenPrepCard(
    tasks: List<KitchenPrep.Task>,
    onBreakdownDone: () -> Unit,
    onStockMade: (KitchenPrep.MakeStock) -> Unit,
    onFreezeFrame: (KitchenPrep.MakeStock) -> Unit,
    onThawed: (KitchenPrep.Move) -> Unit,
    onFrozen: (KitchenPrep.Move) -> Unit,
) {
    if (tasks.isEmpty()) return
    SectionCard {
        SectionLabel("Kitchen prep today", color = Leaf)
        tasks.forEach { task ->
            when (task) {
                is KitchenPrep.Breakdown -> BreakdownTask(task, onBreakdownDone)
                is KitchenPrep.MakeStock -> StockTask(task, onStockMade, onFreezeFrame)
                is KitchenPrep.Thaw -> MoveTask(
                    "Thaw in the fridge",
                    "Tonight for tomorrow's meals; for today, a sealed bag in cold water (change the water every 30 min).",
                    task.items, "Moved", onThawed,
                )
                is KitchenPrep.Freeze -> MoveTask(
                    "Freeze before it goes off",
                    "Not planned before its date – freeze it flat in a labelled bag and Mango plans it later.",
                    task.items, "Frozen", onFrozen,
                )
            }
        }
    }
}

@Composable
private fun TaskTitle(text: String) = Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))

@Composable
private fun Hint(text: String) = Text(text, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun BreakdownTask(task: KitchenPrep.Breakdown, onDone: () -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    TaskTitle(if (task.birds == 1) "Break down your chicken (15 min)" else "Break down your ${task.birds} chickens (15 min each)")
    if (task.fridge.isNotEmpty()) Text("Fridge: " + task.fridge.joinToString(", "), fontSize = 14.sp)
    if (task.freezer.isNotEmpty()) Text("Freezer: " + task.freezer.joinToString(", "), fontSize = 14.sp, color = Sky)
    if (open) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf(
                "Use a separate board; wash hands, knife and board afterwards.",
                "Legs: cut through the skin between leg and body, bend the leg back to pop the hip joint and cut through it. Split each leg at the knee joint into thigh and drumstick.",
                "Wings: cut through the shoulder joint. The tips go with the frame for stock.",
                "Breasts: cut along each side of the breastbone and follow the ribs with the knife to lift the fillets off.",
                "Boneless thighs: cut along the bone and lift it out – the bones join the frame.",
                "Freezer pieces: flat in labelled, dated bags, air pressed out.",
            ).forEachIndexed { i, s -> Text("${i + 1}. $s", fontSize = 13.sp) }
            Hint("10-piece method as in Andy Cooks' ayam goreng; cook chicken to 74 °C at the thickest part.")
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = onDone) { Text("Done") }
        TextButton(onClick = { open = !open }) { Text(if (open) "Hide steps" else "How to") }
    }
}

@Composable
private fun StockTask(task: KitchenPrep.MakeStock, onMade: (KitchenPrep.MakeStock) -> Unit, onFreeze: (KitchenPrep.MakeStock) -> Unit) {
    TaskTitle("Turn the frame into ~${task.yieldMl} ml of stock")
    Text(
        if (task.pressureCooker) {
            "Pressure cooker: frame, wing tips, an onion, a carrot, a celery stick and a bay leaf, cold water to cover (below the max line). High pressure 1 hour, then natural release."
        } else {
            "Quick stock (Andy Cooks): cover the frame with water, simmer 25 min, pick off the meat, then simmer the bones 30 min more. " +
                "For a clearer stock: blanch 15 min, throw that water away, simmer 3 h in fresh water with an onion, a carrot and a bay leaf."
        },
        fontSize = 13.sp,
    )
    Hint("Strain, cool fast in an ice bath and shallow boxes, fridge within 2 h. Keeps 3-4 days, or 2-3 months frozen in 250 ml boxes.")
    if (task.framesFrozen >= 2) Hint("You have ${task.framesFrozen} frames frozen: freeze this one too and make a big dark stock (roast the frames 1 h at 220 °C, simmer 4 h).")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { onMade(task) }, modifier = Modifier.weight(1f)) { Text("Stock made") }
        OutlinedButton(onClick = { onFreeze(task) }, modifier = Modifier.weight(1f)) { Text("Freeze the frame") }
    }
}

@Composable
private fun MoveTask(title: String, hint: String, items: List<KitchenPrep.Move>, action: String, onMove: (KitchenPrep.Move) -> Unit) {
    TaskTitle(title)
    Hint(hint)
    items.forEach { m ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(m.label.replaceFirstChar { it.uppercase() }, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(m.reason, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { onMove(m) }) { Text(action) }
        }
    }
}
