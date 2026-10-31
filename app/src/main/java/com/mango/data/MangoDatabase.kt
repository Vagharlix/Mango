package com.mango.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Dao
interface ProfileDao {
    @Query("SELECT * FROM user_profile WHERE id = 1")
    fun observe(): Flow<UserProfile?>

    @Query("SELECT * FROM user_profile WHERE id = 1")
    suspend fun get(): UserProfile?

    @Upsert
    suspend fun upsert(profile: UserProfile)
}

@Dao
interface FoodDao {
    @Query("SELECT * FROM foods ORDER BY name")
    fun observeAll(): Flow<List<Food>>

    @Query("SELECT * FROM foods")
    suspend fun getAll(): List<Food>

    @Query("SELECT * FROM foods WHERE id = :id")
    suspend fun getById(id: Long): Food?

    @Query("SELECT COUNT(*) FROM foods WHERE name = :name")
    suspend fun countByName(name: String): Int

    @Query("SELECT COUNT(*) FROM foods")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(foods: List<Food>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(food: Food): Long
}

@Dao
interface BarcodeDao {
    @Query("SELECT * FROM barcode_products WHERE barcode = :barcode")
    suspend fun get(barcode: String): BarcodeProduct?

    @Upsert
    suspend fun upsert(product: BarcodeProduct)
}

@Dao
interface PantryDao {
    @Query("SELECT * FROM pantry ORDER BY expiresOnEpochDay")
    fun observeAll(): Flow<List<PantryItem>>

    @Query("SELECT * FROM pantry ORDER BY expiresOnEpochDay")
    suspend fun getAll(): List<PantryItem>

    @Insert
    suspend fun insert(item: PantryItem): Long

    @Update
    suspend fun update(item: PantryItem)

    @Query("DELETE FROM pantry WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface RecipeDao {
    @Query("SELECT * FROM recipes ORDER BY name")
    fun observeAll(): Flow<List<Recipe>>

    @Query("SELECT * FROM recipes")
    suspend fun getAll(): List<Recipe>

    @Query("SELECT * FROM recipes WHERE id = :id")
    suspend fun getById(id: Long): Recipe?

    @Query("SELECT COUNT(*) FROM recipes")
    suspend fun count(): Int

    @Insert
    suspend fun insertAll(recipes: List<Recipe>)

    @Insert
    suspend fun insert(recipe: Recipe): Long

    @Update
    suspend fun update(recipe: Recipe)

    @Query("UPDATE recipes SET isHidden = :hidden WHERE id = :id")
    suspend fun setHidden(id: Long, hidden: Boolean)

    @Query("DELETE FROM recipes WHERE id = :id AND isCustom = 1")
    suspend fun deleteCustom(id: Long)
}

@Dao
interface MealSlotDao {
    @Query("SELECT * FROM meal_slots WHERE epochDay BETWEEN :from AND :to ORDER BY epochDay")
    fun observeRange(from: Long, to: Long): Flow<List<MealSlot>>

    @Query("SELECT * FROM meal_slots WHERE epochDay BETWEEN :from AND :to ORDER BY epochDay")
    suspend fun getRange(from: Long, to: Long): List<MealSlot>

    @Upsert
    suspend fun upsertAll(slots: List<MealSlot>)

    @Delete
    suspend fun deleteAll(slots: List<MealSlot>)

    @Query("DELETE FROM meal_slots WHERE status = 'PLANNED' AND recipeId = :recipeId AND epochDay >= :from")
    suspend fun deletePlannedForRecipe(recipeId: Long, from: Long)
}

@Dao
interface ExerciseDao {
    @Query("SELECT * FROM exercises")
    fun observeAll(): Flow<List<Exercise>>

    @Query("SELECT COUNT(*) FROM exercises")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(exercises: List<Exercise>)
}

@Dao
interface BenchmarkDao {
    @Query("SELECT * FROM benchmarks ORDER BY epochDay")
    fun observeAll(): Flow<List<Benchmark>>

    @Insert
    suspend fun insert(benchmark: Benchmark)
}

class Converters {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter fun fromStrings(v: List<String>): String = json.encodeToString(v)
    @TypeConverter fun toStrings(v: String): List<String> = json.decodeFromString(v)
    @TypeConverter fun fromInts(v: List<Int>): String = json.encodeToString(v)
    @TypeConverter fun toInts(v: String): List<Int> = json.decodeFromString(v)
    @TypeConverter fun fromIngredients(v: List<RecipeIngredient>): String = json.encodeToString(v)
    @TypeConverter fun toIngredients(v: String): List<RecipeIngredient> = json.decodeFromString(v)
}

@Database(
    entities = [
        UserProfile::class, Food::class, BarcodeProduct::class, PantryItem::class,
        Recipe::class, MealSlot::class, Exercise::class, Benchmark::class,
    ],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class MangoDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun foodDao(): FoodDao
    abstract fun barcodeDao(): BarcodeDao
    abstract fun pantryDao(): PantryDao
    abstract fun recipeDao(): RecipeDao
    abstract fun mealSlotDao(): MealSlotDao
    abstract fun exerciseDao(): ExerciseDao
    abstract fun benchmarkDao(): BenchmarkDao

    companion object {
        fun create(context: Context): MangoDatabase =
            Room.databaseBuilder(context, MangoDatabase::class.java, "mango.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}
