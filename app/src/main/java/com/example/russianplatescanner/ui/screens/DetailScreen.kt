package com.example.russianplatescanner.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.russianplatescanner.PlateApp
import com.example.russianplatescanner.ui.theme.*
import com.example.russianplatescanner.util.PhotoStorage
import com.example.russianplatescanner.util.formatPlateUi
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun DetailScreen(
    plateId: Long,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val app = context.applicationContext as PlateApp
    val viewModel: DetailViewModel = viewModel(
        factory = DetailViewModelFactory(app.plateDao)
    )

    LaunchedEffect(plateId) {
        viewModel.load(plateId)
    }

    val plate by viewModel.plate.collectAsState()
    var showDeleteDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .statusBarsPadding()
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад", tint = Fg)
            }
            Text(
                plate?.number?.let { formatPlateUi(it) } ?: "Детали",
                color = Fg,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = { showDeleteDialog = true }) {
                Icon(Icons.Outlined.Delete, "Удалить", tint = Danger)
            }
        }

        Spacer(Modifier.height(12.dp))

        plate?.let { p ->
            AsyncImage(
                model = p.photoPath,
                contentDescription = "Фото номера",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(280.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Surface)
                    .border(1.dp, Border, RoundedCornerShape(20.dp)),
                contentScale = ContentScale.Fit
            )
            Spacer(Modifier.height(24.dp))
            Text(
                formatPlateUi(p.number),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                fontSize = 28.sp,
                color = Fg,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            Spacer(Modifier.height(8.dp))
            val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.getDefault()) }
            Text(
                "Добавлено: ${dateFormat.format(Date(p.timestamp))}",
                color = Subtle,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
            p.note?.let { note ->
                Spacer(Modifier.height(16.dp))
                Text(
                    note,
                    color = Fg,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Surface)
                        .padding(16.dp)
                )
            }
        } ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Accent)
        }
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = Surface,
            title = { Text("Удалить запись?", color = Fg) },
            text = { Text("Номер и фото будут удалены безвозвратно.", color = Muted) },
            confirmButton = {
                TextButton(
                    onClick = {
                        plate?.let {
                            PhotoStorage.deletePhoto(it.photoPath)
                            viewModel.delete(it) {
                                showDeleteDialog = false
                                onBack()
                            }
                        }
                    }
                ) { Text("Удалить", color = Danger) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Отмена", color = Muted) }
            }
        )
    }
}
