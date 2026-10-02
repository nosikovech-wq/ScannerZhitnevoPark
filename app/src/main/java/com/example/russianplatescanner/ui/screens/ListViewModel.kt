package com.example.russianplatescanner.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import android.content.Context
import com.example.russianplatescanner.data.PlateDao
import com.example.russianplatescanner.data.PlateEntity
import com.example.russianplatescanner.util.PhotoStorage
import com.example.russianplatescanner.util.SheetSync
import com.example.russianplatescanner.util.correctPlate
import com.example.russianplatescanner.util.normalizePlate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ListViewModel(
    private val plateDao: PlateDao,
    private val appContext: Context
) : ViewModel() {

    private val _plates = MutableStateFlow<List<PlateEntity>>(emptyList())
    val plates: StateFlow<List<PlateEntity>> = _plates.asStateFlow()

    private val _pendingUpload = MutableStateFlow(0)
    val pendingUpload: StateFlow<Int> = _pendingUpload.asStateFlow()

    private var allPlates: List<PlateEntity> = emptyList()
    private var currentQuery = ""
    private var dayStart: Long? = null
    private val gate = Mutex()
    private var epoch = 0

    fun currentEpoch(): Int = epoch

    fun markUploaded(uids: List<String>) {
        if (uids.isEmpty()) return
        viewModelScope.launch {
            gate.withLock {
                epoch++
                uids.chunked(400).forEach { plateDao.markUploaded(it) }
            }
        }
    }

    fun syncFromServer(ids: Set<String>, startedEpoch: Int) {
        viewModelScope.launch {
            gate.withLock {
                if (startedEpoch != epoch) return@withLock
                val uploaded = allPlates.map { it.recordKey() }.filter { it in ids }
                val missing = allPlates.map { it.recordKey() }.filter { it !in ids }
                uploaded.chunked(400).forEach { plateDao.markUploaded(it) }
                missing.chunked(400).forEach { plateDao.markNotUploaded(it) }
            }
        }
    }

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
        onDone: (String) -> Unit
    ) {
        viewModelScope.launch {
            val normalized = correctPlate(number)
            if (normalized.isBlank()) {
                onDone("Номер не распознан")
                return@launch
            }
            val updated = plate.copy(
                number = normalized,
                note = note.trim().ifBlank { null },
                unauthorizedExit = unauthorized
            )
            plateDao.update(updated)
            val url = SheetSync.url(appContext)
            if (!url.startsWith("https://") || !plate.uploaded) {
                onDone("Сохранено на телефоне")
                return@launch
            }
            val synced = runCatching { SheetSync.update(url, updated, plate.number) }.getOrDefault(false)
            onDone(if (synced) "Сохранено и обновлено в таблице" else "Сохранено на телефоне, таблица не обновлена")
        }
    }

    fun delete(plate: PlateEntity, onDone: () -> Unit) {
        viewModelScope.launch {
            PhotoStorage.deletePhoto(plate.photoPath)
            plateDao.delete(plate)
            val url = SheetSync.url(appContext)
            if (url.startsWith("https://")) {
                runCatching { SheetSync.remove(url, plate) }
            }
            onDone()
        }
    }

    fun search(query: String) {
        currentQuery = query.trim()
        publish()
    }

    fun setDay(start: Long?) {
        dayStart = start
        publish()
    }

    private fun publish() {
        val query = currentQuery
        val start = dayStart
        val needle = normalizePlate(query)
        _plates.value = allPlates.filter { plate ->
            val matchesDay = start == null || plate.timestamp in start until start + DAY_MS
            val matchesQuery = needle.isBlank() || normalizePlate(plate.number).contains(needle)
            matchesDay && matchesQuery
        }
        _pendingUpload.value = allPlates.count { !it.uploaded }
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
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
