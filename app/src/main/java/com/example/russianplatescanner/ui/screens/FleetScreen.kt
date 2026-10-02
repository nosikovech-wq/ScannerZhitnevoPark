package com.example.russianplatescanner.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.russianplatescanner.ui.theme.Accent
import com.example.russianplatescanner.ui.theme.AccentFg
import com.example.russianplatescanner.ui.theme.Border
import com.example.russianplatescanner.ui.theme.Danger
import com.example.russianplatescanner.ui.theme.Fg
import com.example.russianplatescanner.ui.theme.Muted
import com.example.russianplatescanner.ui.theme.Ok
import com.example.russianplatescanner.ui.theme.Surface2
import com.example.russianplatescanner.util.Crew
import com.example.russianplatescanner.util.FleetBook

@Composable
fun FleetScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var rows by remember { mutableStateOf(FleetBook.crews()) }
    var editing by remember { mutableStateOf<Crew?>(null) }
    var creating by remember { mutableStateOf(false) }

    fun persist(next: List<Crew>) {
        FleetBook.saveLocal(context, next.sortedBy { it.tractor })
        rows = FleetBook.crews()
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) { Text("Назад", color = Accent) }
            Text("Справочник", color = Fg, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
        }
        Text(
            "Номер тягача, прицеп и ФИО. Файл хранится на этом телефоне.",
            color = Muted,
            fontSize = 13.sp
        )
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(rows) { crew ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { editing = crew }
                        .padding(vertical = 8.dp)
                ) {
                    Text(
                        FleetBook.label(crew.tractor, canonical = false),
                        color = Fg,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    )
                    Text(
                        listOf(
                            FleetBook.label(crew.trailer, canonical = false),
                            crew.driver
                        ).filter { it.isNotBlank() }.joinToString(" · "),
                        color = Muted,
                        fontSize = 13.sp
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { creating = true },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Ok, contentColor = AccentFg)
        ) {
            Text("Добавить", color = AccentFg, fontWeight = FontWeight.Bold)
        }
    }

    val current = editing
    if (creating || current != null) {
        CrewDialog(
            crew = current,
            onDismiss = {
                creating = false
                editing = null
            },
            onSave = { tractor, trailer, driver ->
                val without = rows.filterNot { it.sameAs(current) }
                persist(without + Crew(tractor.trim(), trailer.trim(), driver.trim()))
                creating = false
                editing = null
            },
            onDelete = if (current == null) {
                null
            } else {
                {
                    persist(rows.filterNot { it.sameAs(current) })
                    editing = null
                }
            }
        )
    }
}

private fun Crew.sameAs(other: Crew?): Boolean {
    return other != null && tractor == other.tractor && trailer == other.trailer && driver == other.driver
}

@Composable
private fun CrewDialog(
    crew: Crew?,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Unit,
    onDelete: (() -> Unit)?
) {
    var tractor by remember(crew) { mutableStateOf(crew?.tractor.orEmpty()) }
    var trailer by remember(crew) { mutableStateOf(crew?.trailer.orEmpty()) }
    var driver by remember(crew) { mutableStateOf(crew?.driver.orEmpty()) }
    val colors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = Fg,
        unfocusedTextColor = Fg,
        focusedBorderColor = Accent,
        unfocusedBorderColor = Border,
        cursorColor = Fg,
        focusedContainerColor = Surface2,
        unfocusedContainerColor = Surface2
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = com.example.russianplatescanner.ui.theme.Surface,
        title = { Text(if (crew == null) "Новая строка" else "Изменить", color = Fg) },
        text = {
            Column {
                OutlinedTextField(
                    value = tractor,
                    onValueChange = { tractor = it.uppercase() },
                    label = { Text("Номер тягача") },
                    singleLine = true,
                    colors = colors,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = trailer,
                    onValueChange = { trailer = it.uppercase() },
                    label = { Text("Номер прицепа") },
                    singleLine = true,
                    colors = colors,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = driver,
                    onValueChange = { driver = it },
                    label = { Text("ФИО") },
                    singleLine = true,
                    colors = colors,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = tractor.isNotBlank(),
                onClick = { onSave(tractor, trailer, driver) }
            ) { Text("Сохранить", color = Ok) }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("Удалить", color = Danger) }
                }
                TextButton(onClick = onDismiss) { Text("Отмена", color = Muted) }
            }
        }
    )
}
