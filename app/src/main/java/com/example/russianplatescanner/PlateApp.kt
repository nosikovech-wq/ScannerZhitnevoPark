package com.example.russianplatescanner

import android.app.Application
import com.example.russianplatescanner.data.AppDatabase
import com.example.russianplatescanner.data.PlateDao
import com.example.russianplatescanner.util.FleetBook
import com.example.russianplatescanner.util.ParkSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class PlateApp : Application() {
    val database: AppDatabase by lazy { AppDatabase.getDatabase(this) }
    val plateDao: PlateDao by lazy { database.plateDao() }
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        FleetBook.loadLocal(this)
        ParkSync.start(this, appScope)
    }
}
