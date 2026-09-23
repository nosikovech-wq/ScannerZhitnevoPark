package com.example.russianplatescanner

import android.app.Application
import com.example.russianplatescanner.data.AppDatabase
import com.example.russianplatescanner.data.PlateDao

class PlateApp : Application() {
    val database: AppDatabase by lazy { AppDatabase.getDatabase(this) }
    val plateDao: PlateDao by lazy { database.plateDao() }
}
