package com.mango.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.mango.data.Food
import com.mango.data.ScanResult
import java.util.concurrent.Executors

@Composable
fun ScannerScreen(onDone: () -> Unit) {
    val vm = mangoViewModel { PantryViewModel(it) }
    val context = LocalContext.current
    val result by vm.scan.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val foods by vm.foods.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }

    Box(Modifier.fillMaxSize()) {
        if (granted) {
            BarcodeCamera(onBarcode = vm::onBarcode)
            Box(
                Modifier
                    .align(Alignment.Center)
                    .size(280.dp, 160.dp)
                    .border(BorderStroke(3.dp, MangoOrange), RoundedCornerShape(16.dp)),
            )
        } else {
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Camera permission is needed to scan barcodes.")
                Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }, Modifier.padding(top = 12.dp)) { Text("Grant permission") }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (busy) CircularProgressIndicator()
            message?.let { Text(it, color = Danger) }
            Text("Point at a UPC / EAN barcode", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onDone, Modifier.padding(top = 8.dp)) { Text("Close") }
        }
    }

    result?.let { r ->
        ScanResultDialog(
            result = r,
            foods = foods,
            onDismiss = vm::dismissScan,
            onAdd = { food, grams, days -> vm.addScanned(r.barcode, productName(r), food, grams, days, onDone) },
            onAddNew = { grams, days -> (r as? ScanResult.Remote)?.let { vm.addAsNewFood(it.product, grams, days, onDone) } },
        )
    }
}

private fun productName(r: ScanResult) = when (r) {
    is ScanResult.Known -> r.productName
    is ScanResult.Remote -> r.product.name
    is ScanResult.Unknown -> "Barcode ${r.barcode}"
}

@OptIn(ExperimentalGetImage::class)
@Composable
private fun BarcodeCamera(onBarcode: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(onBarcode)
    val previewView = remember { PreviewView(context) }

    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E)
                .build(),
        )
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor) { proxy ->
                val media = proxy.image
                if (media == null) {
                    proxy.close()
                    return@setAnalyzer
                }
                scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                    .addOnSuccessListener { codes -> codes.firstNotNullOfOrNull { it.rawValue }?.let { latest(it) } }
                    .addOnCompleteListener { proxy.close() }
            }
            try {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
            } catch (e: Exception) {
                android.util.Log.e("Mango", "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            runCatching { providerFuture.get().unbindAll() }
            scanner.close()
            executor.shutdown()
        }
    }
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

@Composable
private fun ScanResultDialog(
    result: ScanResult,
    foods: List<Food>,
    onDismiss: () -> Unit,
    onAdd: (Food, Double, Int) -> Unit,
    onAddNew: (Double, Int) -> Unit,
) {
    val initial = when (result) {
        is ScanResult.Known -> result.food
        is ScanResult.Remote -> result.suggestions.firstOrNull()
        is ScanResult.Unknown -> null
    }
    var food by remember { mutableStateOf(initial) }
    var asNew by remember { mutableStateOf(false) }
    var grams by remember { mutableStateOf(initial?.unitGrams ?: 250.0) }
    var days by remember { mutableStateOf((initial?.shelfLifeDays ?: 14).toDouble()) }
    fun pick(f: Food) {
        food = f
        asNew = false
        days = f.shelfLifeDays.toDouble()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(productName(result)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (result) {
                    is ScanResult.Known -> Text("Found locally (offline) → ${result.food.name}", color = Leaf)
                    is ScanResult.Remote -> {
                        val n = result.product.nutrients
                        Text("From Open Food Facts · per 100 g: ${n.kcal.fmt()} kcal, P ${n.protein.fmt(1)} g, C ${n.carbs.fmt(1)} g, F ${n.fat.fmt(1)} g", style = MaterialTheme.typography.bodySmall)
                        Text("Match it to a catalog food so recipes can use it:")
                        result.suggestions.take(3).forEach { s ->
                            FilterChip(selected = food?.id == s.id && !asNew, onClick = { pick(s) }, label = { Text(s.name) })
                        }
                        FilterChip(selected = asNew, onClick = { asNew = true; food = null }, label = { Text("Save as new food") })
                    }
                    is ScanResult.Unknown -> Text(result.reason, color = Warn)
                }
                if (result !is ScanResult.Known && !asNew) FoodSearch(foods, onPick = ::pick, selected = food)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Quantity", grams, { grams = it }, Modifier.weight(1f), suffix = "g")
                    NumberField("Expires in", days, { days = it }, Modifier.weight(1f), suffix = "days")
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = grams > 0 && (food != null || asNew),
                onClick = {
                    val f = food
                    if (asNew) onAddNew(grams, days.toInt()) else if (f != null) onAdd(f, grams, days.toInt())
                },
            ) { Text("Add to pantry") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Rescan") } },
    )
}
