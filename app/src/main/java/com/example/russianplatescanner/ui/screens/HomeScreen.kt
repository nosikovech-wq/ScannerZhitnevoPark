package com.example.russianplatescanner.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.example.russianplatescanner.ui.theme.Bg
import com.example.russianplatescanner.ui.theme.Fg
import com.example.russianplatescanner.ui.theme.Muted
import com.example.russianplatescanner.ui.theme.Subtle
import com.example.russianplatescanner.ui.theme.Surface
import com.example.russianplatescanner.ui.theme.Surface2

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
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(12.dp))
        Text("ЛОКАЛЬНАЯ БАЗА", style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(4.dp))
        Text("ЖитневоПарк Сканер", style = androidx.compose.material3.MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Автор ПО, 8-999-846-90-96",
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
        )
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
                icon = { Icon(Icons.Outlined.ListAlt, null, modifier = Modifier.size(16.dp)) },
                onClick = { tab = 1 },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(16.dp))

        Box(Modifier.weight(1f)) {
            if (tab == 0) {
                CameraScreen(embedded = true, onSaved = { tab = 1 }, onBack = {})
            } else {
                ListScreen(embedded = true, onItemClick = onItemClick)
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
