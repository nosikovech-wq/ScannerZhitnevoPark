package com.example.russianplatescanner.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.russianplatescanner.data.PlateDao
import com.example.russianplatescanner.data.PlateEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ListViewModel(private val plateDao: PlateDao) : ViewModel() {

    private val _plates = MutableStateFlow<List<PlateEntity>>(emptyList())
    val plates: StateFlow<List<PlateEntity>> = _plates.asStateFlow()

    private var allPlates: List<PlateEntity> = emptyList()
    private var currentQuery = ""
    private var dayStart: Long? = null

    init {
        viewModelScope.launch {
            plateDao.getAll().collectLatest { list ->
                allPlates = list
                publish()
            }
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
        _plates.value = allPlates.filter { plate ->
            val matchesDay = start == null || plate.timestamp in start until start + DAY_MS
            val matchesQuery = query.isBlank() || plate.number.contains(query, ignoreCase = true)
            matchesDay && matchesQuery
        }
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}

class ListViewModelFactory(private val plateDao: PlateDao) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ListViewModel::class.java)) {
            return ListViewModel(plateDao) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
