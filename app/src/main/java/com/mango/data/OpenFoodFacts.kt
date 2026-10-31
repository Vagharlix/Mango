package com.mango.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

data class OffProduct(
    val barcode: String,
    val name: String,
    val categories: String,
    /** Per 100 g. */
    val nutrients: Nutrients,
)

/** Optional online fallback for unknown barcodes. Results are cached in Room so each barcode is fetched once. */
object OpenFoodFacts {
    private val json = Json { ignoreUnknownKeys = true }

    /** Returns null when the product is unknown; throws [IOException] on network failure. */
    fun fetch(barcode: String): OffProduct? {
        require(barcode.all { it.isDigit() }) { "Invalid barcode" }
        val url = URI(
            "https://world.openfoodfacts.org/api/v2/product/$barcode.json" +
                "?fields=product_name,brands,categories,nutriments",
        ).toURL()
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8_000
            conn.readTimeout = 8_000
            conn.setRequestProperty("User-Agent", "Mango/1.0 (Android; offline-first training app)")
            if (conn.responseCode == HttpURLConnection.HTTP_NOT_FOUND) return null
            if (conn.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${conn.responseCode}")
            return parse(barcode, conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    fun parse(barcode: String, body: String): OffProduct? {
        val root = json.parseToJsonElement(body).jsonObject
        if (root["status"]?.jsonPrimitive?.intOrNull != 1) return null
        val product = root["product"]?.jsonObject ?: return null
        val n = product["nutriments"]?.jsonObject ?: JsonObject(emptyMap())
        fun num(key: String) = n[key]?.jsonPrimitive?.doubleOrNull
        fun str(key: String) = product[key]?.jsonPrimitive?.content?.trim().orEmpty()

        val name = listOf(str("brands").substringBefore(','), str("product_name"))
            .filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Product $barcode" }
        val kcal = num("energy-kcal_100g") ?: num("energy_100g")?.div(4.184) ?: 0.0
        return OffProduct(
            barcode = barcode,
            name = name,
            categories = str("categories"),
            nutrients = Nutrients(
                kcal = kcal,
                protein = num("proteins_100g") ?: 0.0,
                carbs = num("carbohydrates_100g") ?: 0.0,
                fat = num("fat_100g") ?: 0.0,
                fiber = num("fiber_100g") ?: 0.0,
                vitaminC = (num("vitamin-c_100g") ?: 0.0) * 1000, // OFF reports grams
                omega3 = num("omega-3-fat_100g") ?: 0.0,
            ),
        )
    }
}
