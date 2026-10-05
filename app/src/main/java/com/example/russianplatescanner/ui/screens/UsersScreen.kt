package com.example.russianplatescanner.ui.screens

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import com.example.russianplatescanner.util.ParkSync
import com.example.russianplatescanner.util.ParkUser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun UsersScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var users by remember { mutableStateOf<List<ParkUser>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }

    fun reload() {
        scope.launch {
            try {
                users = withContext(Dispatchers.IO) { ParkSync.users(context) }
                error = null
            } catch (e: Exception) {
                error = e.message
            }
        }
    }

    LaunchedEffect(Unit) { reload() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Назад", color = Accent) }
            Text("Учётные записи", color = Fg, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
        }
        Text("Оператор сканирует. Администратор ещё правит справочник и заводит людей.", color = Muted, fontSize = 13.sp)
        error?.let { Text(it, color = Danger, fontSize = 13.sp) }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f)) {
            items(users) { user ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(user.username, color = Fg, fontWeight = FontWeight.SemiBold)
                        Text(if (user.role == "admin") "Администратор" else "Оператор", color = Muted, fontSize = 13.sp)
                    }
                    TextButton(onClick = {
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { ParkSync.deleteUser(context, user.id) }
                                reload()
                            } catch (e: Exception) {
                                error = e.message
                            }
                        }
                    }) { Text("Удалить", color = Danger) }
                }
            }
        }
        Button(
            onClick = { creating = true },
            modifier = Modifier.fillMaxWidth().height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Ok, contentColor = AccentFg)
        ) { Text("Добавить", color = AccentFg, fontWeight = FontWeight.Bold) }
    }

    if (creating) {
        CreateUserDialog(
            onDismiss = { creating = false },
            onSave = { name, password, admin ->
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) { ParkSync.createUser(context, name, password, admin) }
                        creating = false
                        reload()
                    } catch (e: Exception) {
                        error = e.message
                        creating = false
                    }
                }
            }
        )
    }
}

@Composable
private fun CreateUserDialog(
    onDismiss: () -> Unit,
    onSave: (String, String, Boolean) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var admin by remember { mutableStateOf(false) }
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
        title = { Text("Новая учётка", color = Fg) },
        text = {
            Column {
                OutlinedTextField(name, { name = it.trim() }, label = { Text("Логин") }, singleLine = true, colors = colors, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    password,
                    { password = it },
                    label = { Text("Пароль") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    colors = colors,
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(onClick = { admin = !admin }) {
                    Text(if (admin) "Роль: администратор" else "Роль: оператор", color = Accent)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.length >= 2 && password.length >= 4, onClick = { onSave(name, password, admin) }) {
                Text("Создать", color = Ok)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена", color = Muted) } }
    )
}
