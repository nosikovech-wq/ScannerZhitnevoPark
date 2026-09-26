package com.example.russianplatescanner.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
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
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.example.russianplatescanner.PlateApp
import com.example.russianplatescanner.data.PlateEntity
import com.example.russianplatescanner.ui.theme.*
import com.example.russianplatescanner.util.CsvExporter
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun ListScreen(
    onAddClick: () -> Unit,
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .then(if (embedded) Modifier else Modifier.statusBarsPadding())
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = {
                    searchQuery = it
                    viewModel.search(it)
                },
                modifier = Modifier.weight(1f),
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
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = { CsvExporter.share(context, plates) },
                enabled = plates.isNotEmpty(),
                modifier = Modifier
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Surface)
                    .border(1.dp, Border, RoundedCornerShape(12.dp))
            ) {
                Icon(Icons.Outlined.FileDownload, contentDescription = "Excel", tint = Fg, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Excel", color = Fg)
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
