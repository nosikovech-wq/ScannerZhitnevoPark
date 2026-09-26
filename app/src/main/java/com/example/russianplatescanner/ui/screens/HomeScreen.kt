package com.example.russianplatescanner.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.russianplatescanner.PlateApp
import com.example.russianplatescanner.ui.theme.Accent
import com.example.russianplatescanner.ui.theme.AccentFg
import com.example.russianplatescanner.ui.theme.Bg
import com.example.russianplatescanner.ui.theme.Border
import com.example.russianplatescanner.ui.theme.Danger
import com.example.russianplatescanner.ui.theme.Fg
import com.example.russianplatescanner.ui.theme.Muted
import com.example.russianplatescanner.ui.theme.Ok
import com.example.russianplatescanner.ui.theme.Subtle
import com.example.russianplatescanner.ui.theme.Surface
import com.example.russianplatescanner.ui.theme.Surface2
import com.example.russianplatescanner.util.PhotoStorage
import com.example.russianplatescanner.util.SheetSync
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    onItemClick: (Long) -> Unit
) {
    var tab by remember { mutableStateOf(0) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.size(36.dp))
            Text(
                "Житнево Парк Сканер",
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = androidx.compose.material3.MaterialTheme.typography.titleLarge
            )
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (tab == 2) Surface2 else Surface)
                    .clickable { tab = if (tab == 2) 0 else 2 },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.Settings,
                    contentDescription = "Настройки",
                    tint = if (tab == 2) Fg else Muted,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Surface)
                .padding(4.dp)
        ) {
            TabChip(
                selected = tab == 0,
                label = "Сканер",
                icon = { Icon(Icons.Outlined.CameraAlt, null, modifier = Modifier.size(16.dp)) },
                onClick = { tab = 0 },
                modifier = Modifier.weight(1f)
            )
            TabChip(
                selected = tab == 1,
                label = "Журнал",
                icon = { Icon(Icons.AutoMirrored.Outlined.Article, null, modifier = Modifier.size(16.dp)) },
                onClick = { tab = 1 },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(16.dp))

        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> CameraScreen(embedded = true, onSaved = { tab = 1 }, onBack = {})
                1 -> ListScreen(embedded = true, onItemClick = onItemClick)
                else -> SettingsScreen()
            }
        }
    }
}

@Composable
private fun TabChip(
    selected: Boolean,
    label: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Surface2 else Surface)
            .clickable(onClick = onClick)
            .height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        icon()
        Spacer(Modifier.width(8.dp))
        Text(label, color = if (selected) Fg else Muted)
    }
}

@Composable
private fun SettingsScreen() {
    val context = LocalContext.current
    val app = context.applicationContext as PlateApp
    var url by remember { mutableStateOf(SheetSync.url(context)) }
    var saved by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var clearMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        Text("Настройки", color = Fg, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
        Spacer(Modifier.height(16.dp))
        Text("Ссылка на скрипт таблицы", color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = url,
            onValueChange = {
                url = it
                saved = false
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("https://script.google.com/.../exec") },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Fg,
                unfocusedTextColor = Fg,
                focusedBorderColor = Accent,
                unfocusedBorderColor = Border,
                cursorColor = Fg,
                focusedContainerColor = Surface,
                unfocusedContainerColor = Surface
            )
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Адрес веб-приложения Google. Должен заканчиваться на /exec.",
            color = Subtle,
            fontSize = 13.sp
        )
        Spacer(Modifier.weight(1f))
        Text(
            "Автор ПО - Telegram",
            color = Accent,
            fontSize = 15.sp,
            textAlign = TextAlign.Center,
            textDecoration = TextDecoration.Underline,
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/helloexec")))
                }
                .padding(vertical = 12.dp)
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                clearMessage = null
                confirmClear = true
            },
            enabled = !clearing,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Danger,
                contentColor = Fg,
                disabledContainerColor = Surface2,
                disabledContentColor = Muted
            )
        ) {
            Text("Очистить базу", color = Fg, fontWeight = FontWeight.Bold)
        }
        clearMessage?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = if (it.startsWith("База")) Ok else Danger, fontSize = 14.sp)
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                SheetSync.saveUrl(context, url)
                saved = true
            },
            enabled = url.trim().startsWith("https://"),
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Ok,
                contentColor = AccentFg,
                disabledContainerColor = Surface2,
                disabledContentColor = Muted
            )
        ) {
            Text("Сохранить", color = AccentFg, fontWeight = FontWeight.Bold)
        }
        if (saved) {
            Spacer(Modifier.height(12.dp))
            Text("Сохранено", color = Ok, fontSize = 14.sp)
        }
    }

    if (confirmClear) {
        ClearDatabaseDialog(
            busy = clearing,
            onDismiss = { if (!clearing) confirmClear = false },
            onConfirm = {
                scope.launch {
                    clearing = true
                    try {
                        SheetSync.clear(SheetSync.url(context))
                        val paths = app.plateDao.allPhotoPaths()
                        PhotoStorage.deleteAll(context, paths)
                        app.plateDao.deleteAll()
                        clearMessage = "База, фото и онлайн-таблица очищены"
                        confirmClear = false
                    } catch (e: Exception) {
                        clearMessage = e.message ?: "Не удалось очистить базу"
                        confirmClear = false
                    } finally {
                        clearing = false
                    }
                }
            }
        )
    }
}

@Composable
private fun ClearDatabaseDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    var password by remember { mutableStateOf("") }
    val passwordOk = password == "Valter2018dvo"
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(28.dp))
                .background(Surface)
                .padding(20.dp)
        ) {
            Text("Очистить базу?", color = Fg, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                "Будут удалены все записи, фотографии и строки онлайн-таблицы. Вернуть их будет нельзя.",
                color = Muted,
                fontSize = 14.sp
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Пароль") },
                visualTransformation = PasswordVisualTransformation(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Fg,
                    unfocusedTextColor = Fg,
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = Border,
                    focusedLabelColor = Muted,
                    unfocusedLabelColor = Muted,
                    cursorColor = Fg,
                    focusedContainerColor = Surface2,
                    unfocusedContainerColor = Surface2
                )
            )
            if (password.isNotEmpty() && !passwordOk) {
                Spacer(Modifier.height(6.dp))
                Text("Неверный пароль", color = Danger, fontSize = 13.sp)
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onDismiss,
                    enabled = !busy,
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Fg, contentColor = AccentFg)
                ) {
                    Text("Отмена", color = AccentFg, maxLines = 1)
                }
                Button(
                    onClick = onConfirm,
                    enabled = passwordOk && !busy,
                    modifier = Modifier.weight(1f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Danger,
                        contentColor = Fg,
                        disabledContainerColor = Surface2,
                        disabledContentColor = Muted
                    )
                ) {
                    Text(
                        if (busy) "..." else "ОЧИСТИТЬ",
                        color = if (passwordOk && !busy) Fg else Muted,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}
