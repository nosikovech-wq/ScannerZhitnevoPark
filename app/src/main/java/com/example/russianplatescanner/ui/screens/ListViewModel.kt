package com.example.russianplatescanner.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.example.russianplatescanner.data.PlateDao
import com.example.russianplatescanner.data.PlateEntity
import com.example.russianplatescanner.util.FleetBook
import com.example.russianplatescanner.util.ParkSync
import com.example.russianplatescanner.util.PhotoStorage
import com.example.russianplatescanner.util.REPEAT_LOCK_MS
import com.example.russianplatescanner.util.formatPlateUi
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ListViewModel(
    private val plateDao: PlateDao,
    private val appContext: Context
) : ViewModel() {

    private val _plates = MutableStateFlow<List<PlateEntity>>(emptyList())
    val plates: StateFlow<List<PlateEntity>> = _plates.asStateFlow()

    private var allPlates: List<PlateEntity> = emptyList()
    private val gate = Mutex()

    init {
        viewModelScope.launch {
            plateDao.getAll().collectLatest { list ->
                allPlates = list
                publish()
            }
        }
    }

    fun update(
        plate: PlateEntity,
        number: String,
        note: String,
        unauthorized: Boolean,
        onDone: (EditResult) -> Unit
    ) {
        viewModelScope.launch {
            val canonical = FleetBook.resolve(number)
            if (canonical.isBlank()) {
                onDone(EditResult.Saved("Номер не распознан"))
                return@launch
            }
            val sameNumber = FleetBook.resolve(plate.number) == canonical
            val nearby = allPlates
                .filter { it.id != plate.id && FleetBook.resolve(it.number) == canonical }
                .filter { abs(it.timestamp - plate.timestamp) < REPEAT_LOCK_MS }
                .minByOrNull { abs(it.timestamp - plate.timestamp) }
            if (!sameNumber && nearby != null) {
                onDone(EditResult.InsideWindow(canonical, nearby.timestamp))
                return@launch
            }
            val joined = !sameNumber && allPlates.any { it.id != plate.id && FleetBook.resolve(it.number) == canonical }
            val updated = plate.copy(
                number = canonical,
                note = note.trim().ifBlank { null },
                unauthorizedExit = unauthorized,
                pendingSync = true
            )
            plateDao.update(updated)
            runCatching { withContext(Dispatchers.IO) { ParkSync.pushOne(appContext, plateDao, updated) } }
            val saved = if (joined) {
                "Запись добавлена к номеру ${formatPlateUi(canonical)}"
            } else {
                "Сохранено"
            }
            onDone(EditResult.Saved(saved))
        }
    }

    fun delete(plate: PlateEntity, onDone: () -> Unit) {
        viewModelScope.launch {
            PhotoStorage.deletePhoto(plate.photoPath)
            plateDao.delete(plate)
            ParkSync.rememberDelete(appContext, plate.recordKey())
            onDone()
        }
    }

    private fun publish() {
        _plates.value = allPlates
    }
}

sealed class EditResult {
    data class Saved(val message: String) : EditResult()
    data class InsideWindow(val number: String, val previousAt: Long) : EditResult()
}

class ListViewModelFactory(
    private val plateDao: PlateDao,
    private val context: Context
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ListViewModel::class.java)) {
            return ListViewModel(plateDao, context.applicationContext) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
