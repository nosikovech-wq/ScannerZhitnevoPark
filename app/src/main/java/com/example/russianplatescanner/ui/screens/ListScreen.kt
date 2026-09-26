package com.example.russianplatescanner.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.russianplatescanner.PlateApp
import com.example.russianplatescanner.data.PlateEntity
import com.example.russianplatescanner.ui.theme.*
import com.example.russianplatescanner.util.CsvExporter
import com.example.russianplatescanner.util.SheetSync
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ListScreen(
    onItemClick: (Long) -> Unit,
    embedded: Boolean = false
) {
    val context = LocalContext.current
    val app = context.applicationContext as PlateApp
    val viewModel: ListViewModel = viewModel(
        factory = ListViewModelFactory(app.plateDao)
    )

    val plates by viewModel.plates.collectAsState(initial = emptyList())
    var searchQuery by remember { mutableStateOf("") }
    var upload by remember { mutableStateOf<SheetSync.Tick?>(null) }
    var cancelUpload by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun startUpload() {
        val url = SheetSync.url(context)
        if (!url.startsWith("https://")) {
            upload = SheetSync.Tick(
                phase = "Нет адреса скрипта",
                done = 0,
                total = 0,
                inserted = 0,
                skipped = 0,
                finished = true,
                error = "Укажите ссылку в настройках."
            )
            return
        }
        cancelUpload = false
        upload = SheetSync.Tick("Проверка строк в таблице…", 0, 0, 0, 0)
        scope.launch {
            SheetSync.upload(url, plates, { cancelUpload }) { tick ->
                upload = tick
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .then(if (embedded) Modifier else Modifier.statusBarsPadding())
    ) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = {
                searchQuery = it
                viewModel.search(it)
            },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Поиск по номеру") },
            leadingIcon = { Icon(Icons.Outlined.Search, null, tint = Subtle) },
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Border,
                unfocusedBorderColor = Border,
                focusedContainerColor = Surface,
                unfocusedContainerColor = Surface,
                focusedTextColor = Fg,
                unfocusedTextColor = Fg,
                cursorColor = Accent
            )
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(
                onClick = { CsvExporter.share(context, plates) },
                enabled = plates.isNotEmpty(),
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Surface)
                    .border(1.dp, Border, RoundedCornerShape(12.dp))
            ) {
                Icon(Icons.Outlined.FileDownload, contentDescription = "Excel", tint = Fg, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Excel", color = Fg)
            }
            TextButton(
                onClick = { startUpload() },
                enabled = plates.isNotEmpty() && upload?.finished != false,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Surface)
                    .border(1.dp, Border, RoundedCornerShape(12.dp))
            ) {
                Icon(Icons.Outlined.CloudUpload, contentDescription = "Онлайн", tint = Fg, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Онлайн", color = Fg)
            }
        }

        Spacer(Modifier.height(16.dp))

        if (plates.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Border, RoundedCornerShape(20.dp))
                    .padding(horizontal = 20.dp, vertical = 56.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (searchQuery.isBlank())
                        "База пуста. Отсканируйте номер."
                    else "Ничего не найдено",
                    color = Muted
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(plates, key = { it.id }) { plate ->
                    PlateItem(plate = plate, onClick = { onItemClick(plate.id) })
                }
            }
        }
    }

    upload?.let { tick ->
        UploadDialog(
            tick = tick,
            onCancel = { cancelUpload = true },
            onClose = { upload = null }
        )
    }
}

@Composable
fun PlateItem(plate: PlateEntity, onClick: () -> Unit) {
    val dateFormat = remember { SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Surface)
            .border(1.dp, Border, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = plate.photoPath,
            contentDescription = null,
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(8.dp)),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = formatPlateUi(plate.number),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
                color = Fg
            )
            Text(
                text = dateFormat.format(Date(plate.timestamp)),
                color = Subtle,
                fontSize = 12.sp
            )
            if (plate.unauthorizedExit) {
                Text(
                    text = "НЕСОГЛАСОВАННЫЙ ВЫЕЗД",
                    color = Danger,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            plate.note?.let {
                Text(text = it, color = Muted, fontSize = 14.sp, maxLines = 1)
            }
        }
    }
}

private fun formatPlateUi(number: String): String {
    val m = Regex("^([АВЕКМНОРСТУХ])(\\d{3})([АВЕКМНОРСТУХ]{2})(\\d{2,3})$").find(number)
    return if (m != null) "${m.groupValues[1]} ${m.groupValues[2]} ${m.groupValues[3]} ${m.groupValues[4]}" else number
}

@Composable
private fun UploadDialog(
    tick: SheetSync.Tick,
    onCancel: () -> Unit,
    onClose: () -> Unit
) {
    val fraction = if (tick.total <= 0) 0f else tick.done.toFloat() / tick.total.toFloat()
    Dialog(onDismissRequest = { if (tick.finished) onClose() else onCancel() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(Surface)
                .padding(20.dp)
        ) {
            Text("Выгрузка в таблицу", color = Fg, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
            Spacer(Modifier.height(12.dp))
            Text(tick.phase, color = Fg, fontSize = 16.sp)
            Spacer(Modifier.height(12.dp))
            if (!tick.finished && tick.total == 0) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    color = Ok,
                    trackColor = Surface2
                )
            } else {
                LinearProgressIndicator(
                    progress = { if (tick.finished && tick.total == 0 && tick.error == null) 1f else fraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    color = if (tick.error == null) Ok else Danger,
                    trackColor = Surface2
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (tick.total > 0) "${tick.done} из ${tick.total}" else "Сверка с таблицей",
                color = Muted,
                fontSize = 13.sp
            )
            if (tick.finished) {
                Spacer(Modifier.height(8.dp))
                Text(
                    tick.error ?: "Добавлено: ${tick.inserted}. Уже были в таблице: ${tick.skipped}.",
                    color = if (tick.error == null) Fg else Danger,
                    fontSize = 14.sp
                )
            }
            Spacer(Modifier.height(16.dp))
            if (tick.finished) {
                Button(
                    onClick = onClose,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Ok, contentColor = AccentFg)
                ) { Text("Закрыть", color = AccentFg, fontWeight = FontWeight.Bold, maxLines = 1) }
            } else {
                Button(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Fg, contentColor = AccentFg)
                ) { Text("Отмена", color = AccentFg) }
            }
        }
    }
}
