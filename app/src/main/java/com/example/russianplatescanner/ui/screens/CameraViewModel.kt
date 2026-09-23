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
import com.example.russianplatescanner.util.normalizePlate
import com.example.russianplatescanner.util.startOfLocalDay
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

sealed class SaveResult {
    data object Saved : SaveResult()
    data class Duplicate(val previousAt: Long) : SaveResult()
    data class Failed(val message: String) : SaveResult()
}

class CameraViewModel(
    private val plateDao: PlateDao,
    private val appContext: Context
) : ViewModel() {

    private val recognizer = PlateRecognizer()
    private var analyzing = false
    private var lastAnalyzeAt = 0L
    private var saving = false

    private val _uiState = MutableStateFlow<CameraUiState>(CameraUiState.Idle)
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    private val _liveNumber = MutableStateFlow<String?>(null)
    val liveNumber: StateFlow<String?> = _liveNumber.asStateFlow()

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
                    _liveNumber.value = result.number
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

    fun save(number: String, note: String?, bitmap: Bitmap, onResult: (SaveResult) -> Unit) {
        if (saving) return
        saving = true
        viewModelScope.launch {
            try {
                val normalized = normalizePlate(number)
                if (normalized.isBlank()) {
                    onResult(SaveResult.Failed("Пустой номер"))
                    return@launch
                }
                val since = startOfLocalDay()
                val existing = plateDao.recordedSince(since)
                    .firstOrNull { normalizePlate(it.number) == normalized }
                if (existing != null) {
                    onResult(SaveResult.Duplicate(existing.timestamp))
                    return@launch
                }
                val path = PhotoStorage.savePhoto(appContext, bitmap)
                plateDao.insert(
                    PlateEntity(
                        number = normalized,
                        photoPath = path,
                        note = note?.trim()?.ifBlank { null }
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
