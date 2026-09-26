package com.example.russianplatescanner.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
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
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.russianplatescanner.PlateApp
import com.example.russianplatescanner.ui.theme.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

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
    var torchOn by remember { mutableStateOf(false) }
    val uiState by viewModel.uiState.collectAsState()
    val liveNumber by viewModel.liveNumber.collectAsState()
    val todayHit by viewModel.todayHit.collectAsState()
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
                        PreviewView(ctx).also { pv ->
                            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                            cameraProviderFuture.addListener({
                                val cameraProvider = cameraProviderFuture.get()
                                val preview = Preview.Builder().build()
                                preview.setSurfaceProvider(pv.surfaceProvider)
                                val analysis = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .build()
                                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                                    viewModel.onFrame(imageProxy)
                                }
                                try {
                                    cameraProvider.unbindAll()
                                    val bound = cameraProvider.bindToLifecycle(
                                        lifecycleOwner,
                                        CameraSelector.DEFAULT_BACK_CAMERA,
                                        preview,
                                        imageCapture,
                                        analysis
                                    )
                                    cameraHolder.value = bound
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }, ContextCompat.getMainExecutor(ctx))
                        }
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

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .align(Alignment.TopCenter),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "Реал-тайм OCR",
                    color = Muted,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Bg.copy(alpha = 0.7f))
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                )
                liveNumber?.let { number ->
                    Text(
                        number,
                        color = if (todayHit != null) Danger else Ok,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(
                                if (todayHit != null) Danger.copy(alpha = 0.22f) else Ok.copy(alpha = 0.2f)
                            )
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    )
                }
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
                    if (hit != null) "УЖЕ В БАЗЕ СЕГОДНЯ" else "НОМЕР В КАДРЕ",
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
                            append(". Повторно сохранить нельзя.")
                        },
                        color = Danger,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Text(
                        "Сегодня этого номера в базе ещё нет.",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        val shooting = uiState is CameraUiState.Recognizing
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
        Spacer(Modifier.height(8.dp))
        Text(
            "Реал-тайм читает номер в рамке. Кнопка сохраняет кадр.",
            color = Subtle,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(Modifier.height(12.dp))
    }

    when (val state = uiState) {
        is CameraUiState.Result -> {
            ResultDialog(
                number = state.number,
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
            cameraHolder.value?.cameraControl?.enableTorch(false)
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
                    "Записан сегодня, ${formatWhen(hit.previousAt)}.",
                    color = Fg
                )
                hit.note?.let { note ->
                    Spacer(Modifier.height(8.dp))
                    Text("Заметка: $note", color = Muted)
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Повторная запись этого номера запрещена до полуночи. Завтра его можно сохранить снова.",
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
private fun ResultDialog(
    number: String?,
    onConfirm: (String, String?) -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    var editableNumber by remember { mutableStateOf(number ?: "") }
    var note by remember { mutableStateOf("") }
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

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        containerColor = Surface,
        title = { Text("Сохранить запись", color = Fg) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
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
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (editableNumber.isNotBlank()) {
                        onConfirm(editableNumber.trim(), note.ifBlank { null })
                    }
                },
                enabled = editableNumber.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Fg,
                    contentColor = AccentFg,
                    disabledContainerColor = Surface2,
                    disabledContentColor = Muted
                )
            ) {
                Text("В базу", color = AccentFg, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onRetry) { Text("Ещё раз", color = Muted) }
        }
    )
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
