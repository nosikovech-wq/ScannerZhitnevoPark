package com.example.russianplatescanner.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.annotation.WorkerThread
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.russianplatescanner.PlateApp
import com.example.russianplatescanner.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun CameraScreen(
    onSaved: () -> Unit,
    onBack: () -> Unit,
    embedded: Boolean = false
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val app = context.applicationContext as PlateApp
    val viewModel = viewModel<CameraViewModel>(
        factory = CameraViewModelFactory(app.plateDao, context)
    )

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (!granted) {
            Toast.makeText(context, "Нужно разрешение на камеру", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val imageCapture = remember { ImageCapture.Builder().build() }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val cameraHolder = remember { mutableStateOf<Camera?>(null) }
    val previewHolder = remember { mutableStateOf<PreviewView?>(null) }
    val providerHolder = remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var torchOn by remember { mutableStateOf(false) }
    val uiState by viewModel.uiState.collectAsState()
    val liveNumber by viewModel.liveNumber.collectAsState()
    val todayHit by viewModel.todayHit.collectAsState()
    val counts by viewModel.counts.collectAsState()
    var blocked by remember { mutableStateOf<SaveResult.Duplicate?>(null) }

    LaunchedEffect(torchOn, cameraHolder.value) {
        val bound = cameraHolder.value ?: return@LaunchedEffect
        if (!bound.cameraInfo.hasFlashUnit()) {
            if (torchOn) {
                torchOn = false
                Toast.makeText(context, "На этом устройстве нет фонарика", Toast.LENGTH_SHORT).show()
            }
            return@LaunchedEffect
        }
        bound.cameraControl.enableTorch(torchOn)
    }

    val cameraPaused = uiState is CameraUiState.Result || uiState is CameraUiState.Recognizing
    LaunchedEffect(hasCameraPermission, cameraPaused, previewHolder.value) {
        if (!hasCameraPermission) return@LaunchedEffect
        val previewView = previewHolder.value ?: return@LaunchedEffect
        val provider = providerHolder.value ?: awaitCameraProvider(context).also {
            providerHolder.value = it
        }
        if (cameraPaused) {
            runCatching { cameraHolder.value?.cameraControl?.enableTorch(false) }
            provider.unbindAll()
            cameraHolder.value = null
            return@LaunchedEffect
        }
        val preview = Preview.Builder().build()
        preview.setSurfaceProvider(previewView.surfaceProvider)
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(cameraExecutor) { imageProxy ->
            viewModel.onFrame(imageProxy)
        }
        try {
            provider.unbindAll()
            cameraHolder.value = provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture,
                analysis
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun takeShot() {
        val photoFile = java.io.File(
            context.cacheDir,
            "temp_plate_${System.currentTimeMillis()}.jpg"
        )
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        imageCapture.takePicture(
            outputOptions,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    try {
                        val bitmap = android.graphics.BitmapFactory.decodeFile(photoFile.absolutePath)
                        photoFile.delete()
                        if (bitmap != null) viewModel.recognize(bitmap)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        Toast.makeText(context, "Ошибка съёмки: ${exception.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (!embedded) {
            TextButton(
                onClick = onBack,
                modifier = Modifier.align(Alignment.Start)
            ) {
                Text("Назад", color = Muted)
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(28.dp))
                .border(1.dp, Border, RoundedCornerShape(28.dp))
                .background(Surface)
        ) {
            if (hasCameraPermission) {
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).also { previewHolder.value = it }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Нет доступа к камере", color = Muted)
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.82f)
                    .fillMaxHeight(0.22f)
                    .border(2.dp, Accent.copy(alpha = 0.9f), RoundedCornerShape(8.dp))
            )

            liveNumber?.let { number ->
                Text(
                    number,
                    color = if (todayHit != null) Danger else Fg,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 12.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (todayHit != null) Danger.copy(alpha = 0.22f) else Bg.copy(alpha = 0.72f))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            // flashlight-v3
            IconButton(
                onClick = { torchOn = !torchOn },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (torchOn) Accent else Bg.copy(alpha = 0.72f))
            ) {
                Icon(
                    imageVector = flashlightIcon(torchOn),
                    contentDescription = if (torchOn) "Выключить фонарик" else "Включить фонарик",
                    tint = if (torchOn) AccentFg else Fg
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        liveNumber?.let { number ->
            val hit = todayHit
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (hit != null) Danger.copy(alpha = 0.16f) else Surface)
                    .border(
                        1.dp,
                        if (hit != null) Danger else Border,
                        RoundedCornerShape(20.dp)
                    )
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    if (hit != null) "УЖЕ В БАЗЕ" else "НОМЕР В КАДРЕ",
                    color = if (hit != null) Danger else Subtle,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 1.4.sp
                )
                Text(
                    number,
                    color = if (hit != null) Danger else Fg,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 34.sp,
                    letterSpacing = 1.sp
                )
                if (hit != null) {
                    Text(
                        buildString {
                            append("Записан ")
                            append(formatWhen(hit.previousAt))
                            hit.note?.let { append(" · ").append(it) }
                            append(". Снова можно с ")
                            append(formatWhen(hit.availableAt))
                            append(".")
                        },
                        color = Danger,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Text(
                        "За последние 24 часа этого номера нет.",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        val shooting = uiState is CameraUiState.Recognizing
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            PeriodCount(label = "Сутки", value = counts.day)
            FloatingActionButton(
                onClick = { if (!shooting) takeShot() },
                containerColor = Accent,
                contentColor = AccentFg,
                shape = CircleShape,
                modifier = Modifier.size(64.dp)
            ) {
                if (shooting) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), color = AccentFg, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Camera, "Сфотографировать", modifier = Modifier.size(28.dp))
                }
            }
            PeriodCount(label = "Месяц", value = counts.month)
        }
        Spacer(Modifier.height(20.dp))
    }

    when (val state = uiState) {
        is CameraUiState.Result -> {
            ResultDialog(
                number = state.number,
                recentHit = { viewModel.recentHit(it) },
                onConfirm = { finalNumber, note ->
                    viewModel.save(finalNumber, note, state.bitmap) { result ->
                        when (result) {
                            is SaveResult.Saved -> {
                                blocked = null
                                Toast.makeText(context, "Сохранено: $finalNumber", Toast.LENGTH_SHORT).show()
                                onSaved()
                            }
                            is SaveResult.Duplicate -> blocked = result
                            is SaveResult.Failed -> {
                                Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                },
                onUnauthorized = { finalNumber, note ->
                    viewModel.save(finalNumber, note, state.bitmap, unauthorized = true) { result ->
                        when (result) {
                            is SaveResult.Saved -> {
                                blocked = null
                                Toast.makeText(context, "Несогласованный выезд: $finalNumber", Toast.LENGTH_SHORT).show()
                                onSaved()
                            }
                            is SaveResult.Failed -> {
                                Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                            }
                            is SaveResult.Duplicate -> blocked = result
                        }
                    }
                },
                onRetry = { viewModel.reset() },
                onDismiss = { viewModel.reset() }
            )
        }
        else -> {}
    }

    blocked?.let { dup ->
        DuplicateNotice(
            hit = dup.hit,
            onDismiss = { blocked = null }
        )
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { cameraHolder.value?.cameraControl?.enableTorch(false) }
            providerHolder.value?.unbindAll()
            cameraHolder.value = null
            cameraExecutor.shutdown()
        }
    }
}

@Composable
private fun DuplicateNotice(
    hit: TodayHit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text("Номер уже в базе", color = Danger) },
        text = {
            Column {
                Text(
                    hit.number,
                    color = Danger,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Записан ${formatWhen(hit.previousAt)}.",
                    color = Fg
                )
                hit.note?.let { note ->
                    Spacer(Modifier.height(8.dp))
                    Text("Заметка: $note", color = Muted)
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Повтор можно сохранить с ${formatWhen(hit.availableAt)}. Отсчёт 24 часов идёт от времени записи.",
                    color = Muted
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Danger, contentColor = Fg)
            ) {
                Text("Понятно")
            }
        }
    )
}

private fun formatWhen(timestamp: Long): String {
    return SimpleDateFormat("d MMMM, HH:mm", Locale("ru")).format(Date(timestamp))
}

@Composable
private fun PeriodCount(label: String, value: Int) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(84.dp)
    ) {
        Text(
            text = value.toString(),
            color = Fg,
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            color = Subtle,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun ResultDialog(
    number: String?,
    recentHit: (String) -> TodayHit?,
    onConfirm: (String, String?) -> Unit,
    onUnauthorized: (String, String?) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    var editableNumber by remember { mutableStateOf(number ?: "") }
    var note by remember { mutableStateOf("") }
    val duplicate = recentHit(editableNumber)
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Fg,
        unfocusedTextColor = Fg,
        cursorColor = Fg,
        focusedBorderColor = Accent,
        unfocusedBorderColor = Border,
        focusedLabelColor = Muted,
        unfocusedLabelColor = Muted,
        focusedContainerColor = Surface2,
        unfocusedContainerColor = Surface2
    )
    val numberStyle = TextStyle(
        color = Fg,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        letterSpacing = 1.sp
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(Surface)
                .padding(20.dp)
        ) {
            Text("Сохранить запись", color = Fg, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = editableNumber,
                onValueChange = { editableNumber = it.uppercase() },
                label = { Text("Номер") },
                singleLine = true,
                textStyle = numberStyle,
                colors = fieldColors,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(88.dp)
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                label = { Text("Заметка (необязательно)") },
                textStyle = TextStyle(color = Fg, fontSize = 18.sp),
                colors = fieldColors,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
            )
            if (duplicate != null) {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        if (editableNumber.isNotBlank()) {
                            onUnauthorized(editableNumber.trim(), note.ifBlank { null })
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Danger, contentColor = Fg)
                ) {
                    Text("НЕСОГЛАСОВАННЫЙ ВЫЕЗД", color = Fg, fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onRetry,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Fg, contentColor = AccentFg)
                ) {
                    Text("Ещё раз", color = AccentFg, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
                Button(
                    onClick = {
                        if (editableNumber.isNotBlank()) {
                            onConfirm(editableNumber.trim(), note.ifBlank { null })
                        }
                    },
                    enabled = editableNumber.isNotBlank(),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Ok,
                        contentColor = AccentFg,
                        disabledContainerColor = Surface2,
                        disabledContentColor = Muted
                    )
                ) {
                    Text("В БАЗУ", fontWeight = FontWeight.Bold, maxLines = 1)
                }
            }
        }
    }
}

// camera-provider-io-v3: ожидание камеры не на главном потоке
@WorkerThread
private fun loadCameraProviderBlocking(context: android.content.Context): ProcessCameraProvider {
    return ProcessCameraProvider.getInstance(context).get()
}

private suspend fun awaitCameraProvider(context: android.content.Context): ProcessCameraProvider =
    withContext(Dispatchers.IO) {
        loadCameraProviderBlocking(context)
    }

private val FlashlightOnIcon = flashlightVector(on = true)
private val FlashlightOffIcon = flashlightVector(on = false)

private fun flashlightIcon(on: Boolean): ImageVector = if (on) FlashlightOnIcon else FlashlightOffIcon

private fun flashlightVector(on: Boolean): ImageVector {
    val builder = ImageVector.Builder(
        name = if (on) "FlashlightOn" else "FlashlightOff",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    )
    builder.addPath(
        pathData = PathParser().parsePathString("M8,2h8v2h-8z").toNodes(),
        fill = SolidColor(Color.Black)
    )
    builder.addPath(
        pathData = PathParser().parsePathString(
            "M6,6h12l-1.2,3.2V20c0,0.55 -0.45,1 -1,1h-7.6c-0.55,0 -1,-0.45 -1,-1V9.2L6,6z"
        ).toNodes(),
        fill = SolidColor(Color.Black)
    )
    if (!on) {
        builder.addPath(
            pathData = PathParser().parsePathString("M4,4 L20,20").toNodes(),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.2f
        )
    }
    return builder.build()
}
