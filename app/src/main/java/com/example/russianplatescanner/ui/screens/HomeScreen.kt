package com.example.russianplatescanner.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Info
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
import com.example.russianplatescanner.util.ParkSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(if (tab == 3) Surface2 else Surface)
                    .clickable { tab = if (tab == 3) 0 else 3 },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = "Инструкция",
                    tint = if (tab == 3) Fg else Muted,
                    modifier = Modifier.size(20.dp)
                )
            }
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
                3 -> HelpScreen()
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
private fun HelpScreen() {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Как пользоваться", color = Fg, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
        HelpCard(
            "Сканер",
            "Наведите номер в рамку. Сверху он появится после трёх одинаковых считываний. Если такой номер уже был на этом телефоне за последние 22 часа, надпись станет красной. Слева от кнопки снимка — машины за сутки, справа — за месяц, только по этому телефону. Молния в углу включает фонарик."
        )
        HelpCard(
            "Проверка номера",
            "После снимка камера выключается. Если номер есть в справочнике, подставляется номер тягача, а ниже видны прицеп и ФИО. Номер можно поправить, заметку можно не писать. «В базу» сохраняет запись. «Ещё раз» снимает заново. Красная кнопка «Несогласованный выезд» записывает повтор, если номер уже есть за последние сутки. Обычный повтор без неё не сохраняется."
        )
        HelpCard(
            "Журнал",
            "Поиск ищет по номеру. Под номером видно ФИО из справочника, если номер найден. «Все», «Сегодня» и «Дата» фильтруют список. Карандаш меняет номер, заметку и пометку несогласованного выезда, изменение уходит на сервер. «Excel» сохраняет месяц на телефон. Красная пометка — несогласованный выезд. Корзина удаляет запись с телефона и с сервера."
        )
        HelpCard(
            "Этот телефон",
            "Записи других телефонов приходят сами, если выполнен вход на сервер. Повтор за 22 часа и счётчики учитывают уже полученные записи."
        )
        HelpCard(
            "Настройки",
            "Шестерёнка справа от названия. Там адрес сервера и вход. Справочник, бэкап, восстановление и очистка базы делаются в панели управления, их меняет только администратор."
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun HelpCard(title: String, body: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Surface)
            .border(1.dp, Border, RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Text(title, color = Fg, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Spacer(Modifier.height(8.dp))
        Text(body, color = Muted, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

@Composable
private fun SettingsScreen() {
    val context = LocalContext.current
    var usersOpen by remember { mutableStateOf(false) }
    var serverUrl by remember { mutableStateOf(ParkSync.url(context)) }
    var serverUser by remember { mutableStateOf("") }
    var serverPassword by remember { mutableStateOf("") }
    var serverMessage by remember { mutableStateOf<String?>(null) }
    var sessionName by remember { mutableStateOf(ParkSync.username(context)) }
    var sessionRole by remember { mutableStateOf(ParkSync.role(context)) }
    val scope = rememberCoroutineScope()
    if (usersOpen) {
        UsersScreen(onBack = { usersOpen = false })
        return
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text("Настройки", color = Fg, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
        Spacer(Modifier.height(12.dp))
        Text("Общий сервер", color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = serverUrl,
            onValueChange = { serverUrl = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("http://IP-сервера:8787") },
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
        if (sessionName.isBlank()) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = serverUser,
                onValueChange = { serverUser = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Логин") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Fg,
                    unfocusedTextColor = Fg,
                    cursorColor = Fg,
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = Border,
                    focusedContainerColor = Surface,
                    unfocusedContainerColor = Surface
                )
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = serverPassword,
                onValueChange = { serverPassword = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                placeholder = { Text("Пароль") },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Fg,
                    unfocusedTextColor = Fg,
                    cursorColor = Fg,
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = Border,
                    focusedContainerColor = Surface,
                    unfocusedContainerColor = Surface
                )
            )
        } else {
            Spacer(Modifier.height(8.dp))
            Text(
                "$sessionName · ${if (sessionRole == "admin") "администратор" else "оператор"}",
                color = Ok,
                fontSize = 14.sp
            )
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                ParkSync.saveUrl(context, serverUrl)
                if (sessionName.isNotBlank()) {
                    ParkSync.logout(context)
                    sessionName = ""
                    sessionRole = ""
                    serverMessage = "Вы вышли"
                } else {
                    scope.launch {
                        try {
                            val role = withContext(Dispatchers.IO) {
                                ParkSync.login(context, serverUser, serverPassword)
                            }
                            sessionName = ParkSync.username(context)
                            sessionRole = role
                            serverPassword = ""
                            serverMessage = "Вход выполнен"
                        } catch (e: Exception) {
                            serverMessage = e.message ?: "Не удалось войти"
                        }
                    }
                }
            },
            enabled = serverUrl.trim().startsWith("http"),
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Accent,
                contentColor = AccentFg,
                disabledContainerColor = Surface2,
                disabledContentColor = Muted
            )
        ) {
            Text(
                if (sessionName.isBlank()) "Войти" else "Выйти",
                color = AccentFg,
                fontWeight = FontWeight.Bold
            )
        }
        if (sessionRole == "admin") {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { usersOpen = true },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Surface2, contentColor = Fg)
            ) { Text("Учётные записи", color = Fg, fontWeight = FontWeight.Bold) }
        }
        serverMessage?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = if (it.startsWith("Вход") || it.startsWith("На сервер")) Ok else Danger, fontSize = 14.sp)
        }
        Spacer(Modifier.height(16.dp))
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
    }
}
