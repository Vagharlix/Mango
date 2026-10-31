package com.mango

import android.app.Application
import android.util.Log
import com.mango.data.MangoDatabase
import com.mango.data.Repository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MangoApp : Application() {
    lateinit var repository: Repository
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        repository = Repository(this, MangoDatabase.create(this))
        appScope.launch {
            try {
                repository.seedIfNeeded()
                repository.ensureWeekPlan()
            } catch (e: Exception) {
                Log.e("Mango", "Database seeding failed", e)
            }
        }
    }
}
