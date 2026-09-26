package com.example.russianplatescanner.ui.screens

import android.content.Context
import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.russianplatescanner.data.PlateDao
import com.example.russianplatescanner.data.PlateEntity
import com.example.russianplatescanner.util.PhotoStorage
import com.example.russianplatescanner.util.PlateRecognizer
import com.example.russianplatescanner.util.startOfLocalDay
import com.example.russianplatescanner.util.startOfLocalMonth
import com.example.russianplatescanner.util.REPEAT_LOCK_MS
import com.example.russianplatescanner.util.normalizePlate
import com.example.russianplatescanner.util.repeatWindowStart
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class CameraUiState {
    data object Idle : CameraUiState()
    data object Recognizing : CameraUiState()
    data class Result(
        val number: String?,
        val rawText: String,
        val bitmap: Bitmap
    ) : CameraUiState()
}

data class TodayHit(
    val number: String,
    val previousAt: Long,
    val note: String?,
    val availableAt: Long
)

sealed class SaveResult {
    data object Saved : SaveResult()
    data class Duplicate(val hit: TodayHit) : SaveResult()
    data class Failed(val message: String) : SaveResult()
}

data class PeriodCounts(val day: Int, val month: Int)

class CameraViewModel(
    private val plateDao: PlateDao,
    private val appContext: Context
) : ViewModel() {

    private val recognizer = PlateRecognizer()
    private var analyzing = false
    private var lastAnalyzeAt = 0L
    private var saving = false
    private var cachedPlates: List<PlateEntity> = emptyList()

    private val _uiState = MutableStateFlow<CameraUiState>(CameraUiState.Idle)
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private val _liveNumber = MutableStateFlow<String?>(null)
    val liveNumber: StateFlow<String?> = _liveNumber.asStateFlow()

    private val _todayHit = MutableStateFlow<TodayHit?>(null)
    val todayHit: StateFlow<TodayHit?> = _todayHit.asStateFlow()

    private val _counts = MutableStateFlow(PeriodCounts(0, 0))
    val counts: StateFlow<PeriodCounts> = _counts.asStateFlow()

    init {
        viewModelScope.launch {
            plateDao.getAll().collect { list ->
                cachedPlates = list
                _todayHit.value = hitFor(_liveNumber.value)
                val dayStart = startOfLocalDay()
                val monthStart = startOfLocalMonth()
                _counts.value = PeriodCounts(
                    day = list.count { it.timestamp >= dayStart },
                    month = list.count { it.timestamp >= monthStart }
                )
            }
        }
    }

    private fun hitFor(number: String?): TodayHit? {
        if (number.isNullOrBlank()) return null
        val normalized = normalizePlate(number)
        if (normalized.isBlank()) return null
        val since = repeatWindowStart()
        val hit = cachedPlates
            .filter { it.timestamp >= since && normalizePlate(it.number) == normalized }
            .maxByOrNull { it.timestamp }
            ?: return null
        return TodayHit(
            number = hit.number,
            previousAt = hit.timestamp,
            note = hit.note,
            availableAt = hit.timestamp + REPEAT_LOCK_MS
        )
    }

    private fun publishLive(number: String?) {
        _liveNumber.value = number
        _todayHit.value = hitFor(number)
    }

    fun onFrame(imageProxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (analyzing || now - lastAnalyzeAt < 450) {
            imageProxy.close()
            return
        }
        if (_uiState.value is CameraUiState.Recognizing || _uiState.value is CameraUiState.Result) {
            imageProxy.close()
            return
        }
        val media = imageProxy.image
        if (media == null) {
            imageProxy.close()
            return
        }
        analyzing = true
        lastAnalyzeAt = now
        val image = InputImage.fromMediaImage(media, imageProxy.imageInfo.rotationDegrees)
        viewModelScope.launch {
            try {
                val result = recognizer.recognize(image)
                if (result.number != null) {
                    publishLive(result.number)
                }
            } catch (_: Exception) {
            } finally {
                imageProxy.close()
                analyzing = false
            }
        }
    }

    fun recognize(bitmap: Bitmap) {
        _uiState.value = CameraUiState.Recognizing
        viewModelScope.launch {
            try {
                val result = recognizer.recognize(bitmap)
                _uiState.value = CameraUiState.Result(
                    number = result.number ?: _liveNumber.value,
                    rawText = result.rawText,
                    bitmap = bitmap
                )
            } catch (e: Exception) {
                _uiState.value = CameraUiState.Result(
                    number = _liveNumber.value,
                    rawText = "Ошибка: ${e.message}",
                    bitmap = bitmap
                )
            }
        }
    }

    fun recentHit(number: String): TodayHit? = hitFor(number)

    fun save(
        number: String,
        note: String?,
        bitmap: Bitmap,
        unauthorized: Boolean = false,
        onResult: (SaveResult) -> Unit
    ) {
        if (saving) return
        saving = true
        viewModelScope.launch {
            try {
                val normalized = normalizePlate(number)
                if (normalized.isBlank()) {
                    onResult(SaveResult.Failed("Пустой номер"))
                    return@launch
                }
                val since = repeatWindowStart()
                val existing = plateDao.recordedSince(since)
                    .filter { normalizePlate(it.number) == normalized }
                    .maxByOrNull { it.timestamp }
                    ?.let {
                        TodayHit(
                            number = it.number,
                            previousAt = it.timestamp,
                            note = it.note,
                            availableAt = it.timestamp + REPEAT_LOCK_MS
                        )
                    }
                if (existing != null && !unauthorized) {
                    onResult(SaveResult.Duplicate(existing))
                    return@launch
                }
                val path = PhotoStorage.savePhoto(appContext, bitmap)
                plateDao.insert(
                    PlateEntity(
                        number = normalized,
                        photoPath = path,
                        note = note?.trim()?.ifBlank { null },
                        unauthorizedExit = unauthorized && existing != null
                    )
                )
                onResult(SaveResult.Saved)
            } catch (e: Exception) {
                onResult(SaveResult.Failed(e.message ?: "Не удалось сохранить"))
            } finally {
                saving = false
            }
        }
    }

    fun reset() {
        _uiState.value = CameraUiState.Idle
    }
}

class CameraViewModelFactory(
    private val plateDao: PlateDao,
    private val context: Context
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CameraViewModel::class.java)) {
            return CameraViewModel(plateDao, context.applicationContext) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
