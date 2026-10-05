package com.example.russianplatescanner.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.russianplatescanner.data.PlateDao
import com.example.russianplatescanner.data.PlateEntity
import com.example.russianplatescanner.util.ParkSync
import com.example.russianplatescanner.util.PhotoStorage
import com.example.russianplatescanner.util.SheetSync
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import android.content.Context

class DetailViewModel(
    private val plateDao: PlateDao,
    private val appContext: Context
) : ViewModel() {

    private val _plate = MutableStateFlow<PlateEntity?>(null)
    val plate: StateFlow<PlateEntity?> = _plate.asStateFlow()

    fun load(id: Long) {
        viewModelScope.launch {
            _plate.value = plateDao.getById(id)
        }
    }

    fun delete(plate: PlateEntity, onComplete: () -> Unit) {
        viewModelScope.launch {
            PhotoStorage.deletePhoto(plate.photoPath)
            plateDao.delete(plate)
            ParkSync.rememberDelete(appContext, plate.recordKey())
            val url = SheetSync.url(appContext)
            if (url.startsWith("https://")) {
                runCatching { SheetSync.remove(url, plate) }
            }
            onComplete()
        }
    }
}

class DetailViewModelFactory(
    private val plateDao: PlateDao,
    private val context: Context
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DetailViewModel::class.java)) {
            return DetailViewModel(plateDao, context.applicationContext) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
