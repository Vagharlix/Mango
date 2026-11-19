package com.mango.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.mango.data.KitchenTool
import com.mango.data.Repository
import com.mango.data.UserProfile
import com.mango.data.WholeChickenMode
import com.mango.domain.WholeChicken
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class KitchenViewModel(repo: Repository) : MangoViewModel(repo) {
    val profile = repo.profile.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun set(transform: (UserProfile) -> UserProfile) = launchSafe {
        val p = profile.value ?: throw IllegalStateException("Create your profile first")
        val n = transform(p)
        repo.updateKitchen(n.wholeChicken, n.chickenKg, n.kitchen)
        toast("Saved – this week's menu and shopping list updated")
    }
}

val CHICKEN_SIZES = listOf(1.2, 1.4, 1.6, 1.8, 2.0)

fun kitchenSummary(p: UserProfile): String = buildList {
    add("Whole chicken: " + p.wholeChicken.label.lowercase())
    if (p.wholeChicken != WholeChickenMode.OFF) add("${p.chickenKg.fmt(1)} kg")
    p.kitchen.forEach { add(it.label.substringBefore(" /").lowercase()) }
}.joinToString(" · ")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun KitchenScreen(onBack: () -> Unit) {
    val vm = mangoViewModel { KitchenViewModel(it) }
    val p by vm.profile.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.clearMessage()
        }
    }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp, 4.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BackHeader("Kitchen", onBack)
            val profile = p ?: run {
                Text("Create your profile first.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }

            SectionLabel("Whole chicken", Modifier.padding(start = 4.dp, top = 4.dp))
            Text(
                "Mango can buy whole birds and plan the week around every piece: breasts, thighs, drumsticks and wings " +
                    "spread over the week or cooked in one pot, the frame turned into stock, the rest frozen for later.",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            GroupedCard {
                WholeChickenMode.entries.forEachIndexed { i, mode ->
                    if (i > 0) HorizontalDivider(color = Hairline)
                    Row(
                        Modifier.fillMaxWidth().clickable { vm.set { it.copy(wholeChicken = mode) } }.padding(8.dp, 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = profile.wholeChicken == mode, onClick = { vm.set { it.copy(wholeChicken = mode) } })
                        Column(Modifier.weight(1f)) {
                            Text(mode.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            Text(mode.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            if (profile.wholeChicken != WholeChickenMode.OFF) {
                SectionLabel("Size of the bird you buy", Modifier.padding(start = 4.dp, top = 4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CHICKEN_SIZES.forEach { kg ->
                        FilterChip(
                            selected = kotlin.math.abs(profile.chickenKg - kg) < 0.05,
                            onClick = { vm.set { it.copy(chickenKg = kg) } },
                            label = { Text("${kg.fmt(1)} kg") },
                        )
                    }
                }
                Text(
                    "Not sure yet? 1.6 kg is the usual supermarket bird. Mango counts pieces – a thigh is a thigh – so there's nothing to weigh.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SectionCard {
                    SectionLabel("One ${profile.chickenKg.fmt(1)} kg bird gives", color = MangoOrange)
                    WholeChicken.PARTS.forEach { part ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).background(Leaf, CircleShape))
                            Text(part.name(part.pieces), fontSize = 14.sp, modifier = Modifier.weight(1f).padding(start = 10.dp))
                            Text("≈ ${WholeChicken.partGrams(part, profile.chickenKg).fmt()} g", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(
                        "About 5-6 meals for one, plus ~${(WholeChicken.partGrams(WholeChicken.part(WholeChicken.FRAME)!!, profile.chickenKg) * WholeChicken.STOCK_ML_PER_GRAM / 100).toInt() * 100} ml of stock from the frame.",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionLabel("Appliances I have", Modifier.padding(start = 4.dp, top = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KitchenTool.entries.forEach { tool ->
                    FilterChip(
                        selected = tool in profile.kitchen,
                        onClick = { vm.set { it.copy(kitchen = if (tool in it.kitchen) it.kitchen - tool else it.kitchen + tool) } },
                        label = { Text(tool.label) },
                    )
                }
            }
            Text(
                "Pressure cooker: stock in about an hour instead of 1-3 hours on the stove. " +
                    "Deep fryer or air fryer: unlocks fried dishes such as ayam goreng (oven and pan recipes need nothing extra).",
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
    }
}
