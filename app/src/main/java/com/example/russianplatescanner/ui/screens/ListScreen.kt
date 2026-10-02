package com.example.russianplatescanner.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
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
import com.example.russianplatescanner.util.formatPlateUi
import com.example.russianplatescanner.util.normalizePlate
import com.example.russianplatescanner.util.startOfLocalDay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(
    onItemClick: (Long) -> Unit,
    embedded: Boolean = false
) {
    val context = LocalContext.current
    val app = context.applicationContext as PlateApp
    val viewModel: ListViewModel = viewModel(
        factory = ListViewModelFactory(app.plateDao, context.applicationContext)
    )

    val plates by viewModel.plates.collectAsState(initial = emptyList())
    val pendingUpload by viewModel.pendingUpload.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var dayStart by remember { mutableStateOf<Long?>(null) }
    val visible = remember(plates, searchQuery, dayStart) {
        val needle = normalizePlate(searchQuery)
        val start = dayStart
        plates.filter { plate ->
            val matchesDay = start == null || plate.timestamp in start until start + DAY_MS
            val matchesQuery = needle.isEmpty() || normalizePlate(plate.number).contains(needle)
            matchesDay && matchesQuery
        }
    }
    var showDatePicker by remember { mutableStateOf(false) }
    var upload by remember { mutableStateOf<SheetSync.Tick?>(null) }
    var cancelUpload by remember { mutableStateOf(false) }
    var plateToDelete by remember { mutableStateOf<PlateEntity?>(null) }
    var plateToEdit by remember { mutableStateOf<PlateEntity?>(null) }
    var editMessage by remember { mutableStateOf<String?>(null) }
    var windowConflict by remember { mutableStateOf<Pair<PlateEntity, String>?>(null) }
    var deleting by remember { mutableStateOf(false) }
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
            SheetSync.upload(
                url,
                visible,
                { cancelUpload },
                onProgress = { tick -> upload = tick },
                onAccepted = { ids -> viewModel.markUploaded(ids) }
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .then(if (embedded) Modifier else Modifier.statusBarsPadding())
    ) {
        if (pendingUpload > 0) {
            Text(
                "Не выгружено: $pendingUpload",
                color = Fg,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        editMessage?.let {
            Text(
                it,
                color = if (it.startsWith("Сохранено и")) Ok else Fg,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Поиск по номеру") },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, tint = Subtle) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { searchQuery = "" }) {
                        Icon(Icons.Outlined.Close, contentDescription = "Очистить поиск", tint = Subtle)
                    }
                }
            },
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
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            DayChip("Все", dayStart == null) {
                dayStart = null
            }
            DayChip("Сегодня", dayStart == startOfLocalDay()) {
                dayStart = startOfLocalDay()
            }
            DayChip(
                label = if (dayStart != null && dayStart != startOfLocalDay()) {
                    SimpleDateFormat("dd.MM", Locale.getDefault()).format(Date(dayStart!!))
                } else {
                    "Дата"
                },
                selected = dayStart != null && dayStart != startOfLocalDay()
            ) {
                showDatePicker = true
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(
                onClick = { CsvExporter.share(context, visible) },
                enabled = visible.isNotEmpty(),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Surface)
                    .border(1.dp, Border, RoundedCornerShape(12.dp)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
            ) {
                Icon(Icons.Outlined.FileDownload, contentDescription = "Excel", tint = Fg, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Excel", color = Fg)
            }
            TextButton(
                onClick = { startUpload() },
                enabled = visible.isNotEmpty() && upload?.finished != false,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Surface)
                    .border(1.dp, Border, RoundedCornerShape(12.dp)),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
            ) {
                Icon(Icons.Outlined.CloudUpload, contentDescription = "Онлайн", tint = Fg, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Онлайн", color = Fg)
            }
        }

        Spacer(Modifier.height(16.dp))

        if (visible.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Border, RoundedCornerShape(20.dp))
                    .padding(horizontal = 20.dp, vertical = 56.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = when {
                        searchQuery.isNotBlank() -> "Ничего не найдено"
                        dayStart != null -> "За этот день записей нет"
                        else -> "База пуста. Отсканируйте номер."
                    },
                    color = Muted
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(visible, key = { it.id }) { plate ->
                    PlateItem(
                        plate = plate,
                        onClick = { onItemClick(plate.id) },
                        onEdit = { plateToEdit = plate },
                        onDelete = { plateToDelete = plate }
                    )
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

    if (showDatePicker) {
        val pickerState = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { utc ->
                            val picked = Calendar.getInstance().apply { timeInMillis = utc }
                            val local = Calendar.getInstance().apply {
                                set(Calendar.YEAR, picked.get(Calendar.YEAR))
                                set(Calendar.MONTH, picked.get(Calendar.MONTH))
                                set(Calendar.DAY_OF_MONTH, picked.get(Calendar.DAY_OF_MONTH))
                                set(Calendar.HOUR_OF_DAY, 0)
                                set(Calendar.MINUTE, 0)
                                set(Calendar.SECOND, 0)
                                set(Calendar.MILLISECOND, 0)
                            }
                            dayStart = local.timeInMillis
                        }
                        showDatePicker = false
                    }
                ) { Text("Ок", color = AccentFg) }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Отмена", color = Muted) }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }

    plateToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!deleting) plateToDelete = null },
            containerColor = Surface,
            title = { Text("Удалить запись?", color = Fg) },
            text = {
                Text(
                    "Номер ${formatPlateUi(target.number)} будет удалён с телефона, из онлайн-таблицы и с Диска.",
                    color = Muted
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = {
                        deleting = true
                        viewModel.delete(target) {
                            deleting = false
                            plateToDelete = null
                        }
                    }
                ) { Text(if (deleting) "..." else "Удалить", color = Danger) }
            },
            dismissButton = {
                TextButton(
                    enabled = !deleting,
                    onClick = { plateToDelete = null }
                ) { Text("Отмена", color = Muted) }
            }
        )
    }

    plateToEdit?.let { target ->
        EditPlateDialog(
            plate = target,
            onDismiss = { plateToEdit = null },
            onSave = { number, note, unauthorized ->
                viewModel.update(target, number, note, unauthorized) { result ->
                    when (result) {
                        is EditResult.Saved -> {
                            editMessage = result.message
                            plateToEdit = null
                        }
                        is EditResult.InsideWindow -> {
                            val whenText = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                                .format(Date(result.previousAt))
                            windowConflict = target to (
                                "Номер ${formatPlateUi(result.number)} уже поставлен $whenText. " +
                                    "Между записями меньше 22 часов, поэтому время ошибочного номера не переносится. " +
                                    "Ошибочная запись будет удалена."
                                )
                            plateToEdit = null
                        }
                    }
                }
            }
        )
    }

    windowConflict?.let { (wrong, message) ->
        AlertDialog(
            onDismissRequest = { windowConflict = null },
            containerColor = Surface,
            title = { Text("Номер уже в базе", color = Fg) },
            text = { Text(message, color = Fg) },
            confirmButton = {
                TextButton(
                    onClick = {
                        windowConflict = null
                        viewModel.delete(wrong) {
                            editMessage = "Ошибочная запись удалена"
                        }
                    }
                ) { Text("Удалить", color = Danger) }
            },
            dismissButton = {
                TextButton(onClick = { windowConflict = null }) { Text("Отмена", color = Muted) }
            }
        )
    }
}

@Composable
private fun EditPlateDialog(
    plate: PlateEntity,
    onDismiss: () -> Unit,
    onSave: (String, String, Boolean) -> Unit
) {
    var number by remember(plate.id) { mutableStateOf(formatPlateUi(plate.number)) }
    var note by remember(plate.id) { mutableStateOf(plate.note.orEmpty()) }
    var unauthorized by remember(plate.id) { mutableStateOf(plate.unauthorizedExit) }
    var saving by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        containerColor = Surface,
        title = { Text("Изменить запись", color = Fg) },
        text = {
            Column {
                OutlinedTextField(
                    value = number,
                    onValueChange = { number = it.uppercase() },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 20.sp,
                        color = Fg
                    ),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Fg,
                        unfocusedTextColor = Fg,
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = Border,
                        cursorColor = Fg,
                        focusedContainerColor = Surface2,
                        unfocusedContainerColor = Surface2
                    )
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Заметка") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Fg,
                        unfocusedTextColor = Fg,
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = Border,
                        cursorColor = Fg,
                        focusedContainerColor = Surface2,
                        unfocusedContainerColor = Surface2
                    )
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Несогласованный выезд",
                        color = if (unauthorized) Danger else Muted,
                        modifier = Modifier.weight(1f),
                        fontSize = 14.sp
                    )
                    Switch(
                        checked = unauthorized,
                        onCheckedChange = { unauthorized = it },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = Danger,
                            checkedThumbColor = Fg
                        )
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !saving && number.isNotBlank(),
                onClick = {
                    saving = true
                    onSave(number, note, unauthorized)
                }
            ) { Text("Сохранить", color = Ok) }
        },
        dismissButton = {
            TextButton(enabled = !saving, onClick = onDismiss) { Text("Отмена", color = Muted) }
        }
    )
}

@Composable
private fun RowScope.DayChip(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .weight(1f)
            .height(44.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Surface2 else Surface)
            .border(1.dp, if (selected) Fg else Border, RoundedCornerShape(12.dp)),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
    ) {
        Text(label, color = if (selected) Fg else Muted, fontSize = 13.sp, maxLines = 1)
    }
}

@Composable
fun PlateItem(
    plate: PlateEntity,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
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
            if (!plate.uploaded) {
                Text(
                    text = "Не выгружено",
                    color = Fg,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            plate.note?.let {
                Text(text = it, color = Muted, fontSize = 14.sp, maxLines = 1)
            }
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Outlined.Edit, contentDescription = "Изменить", tint = Fg)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.Delete, contentDescription = "Удалить", tint = Danger)
        }
    }
}

private const val DAY_MS = 24L * 60 * 60 * 1000

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
