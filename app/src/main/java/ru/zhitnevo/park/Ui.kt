package ru.zhitnevo.park

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import java.time.ZonedDateTime
import kotlinx.coroutines.delay

private val Ink = Color(ParkColors.INK)
private val Fg = Color(ParkColors.FG)
private val Muted = Color(ParkColors.MUTED)
private val Money = Color(ParkColors.MONEY)
private val Partner = Color(ParkColors.PARTNER)
private val CardBg = Color(0xB30B0C0E)
private val Scheme = darkColorScheme(
    primary = Partner,
    onPrimary = Ink,
    background = Ink,
    surface = Color(0xFF15171B),
    onBackground = Fg,
    onSurface = Fg,
)

@Composable
fun ParkApp(vm: FeedViewModel = viewModel()) {
    MaterialTheme(colorScheme = Scheme) {
        val settings by vm.settings.collectAsState()
        val park by vm.park.collectAsState()
        val partner by vm.partner.collectAsState()
        val parkState by vm.parkState.collectAsState()
        val partnerState by vm.partnerState.collectAsState()
        val frame by vm.cameraBitmap.collectAsState()
        val camStatus by vm.camStatus.collectAsState()
        var open by remember { mutableStateOf(false) }
        var now by remember { mutableStateOf(ZonedDateTime.now(MOSCOW)) }
        LaunchedEffect(Unit) {
            while (true) {
                now = ZonedDateTime.now(MOSCOW)
                delay(1000)
            }
        }
        Box(Modifier.fillMaxSize().background(Ink)) {
            CameraLayer(settings, frame, camStatus, vm::reportCamera)
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = settings.dim)))
            Dashboard(
                park = park,
                partner = partner,
                parkState = parkState,
                partnerState = partnerState,
                camStatus = camStatus,
                now = now,
                onOpenSettings = { open = true },
            )
            if (open) {
                BackHandler { open = false }
                SettingsScreen(
                    initial = settings,
                    onClose = { open = false },
                    onSave = {
                        vm.save(it)
                        open = false
                    },
                )
            }
        }
    }
}

@Composable
private fun CameraLayer(
    settings: AppSettings,
    frame: android.graphics.Bitmap?,
    camStatus: String,
    report: (String) -> Unit,
) {
    if (settings.hikHost.isNotBlank() && settings.hikLive) {
        RtspBackdrop(Hik.rtspUrl(settings), report)
        return
    }
    val bmp = frame
    if (bmp != null && camStatus != "камера не задана") {
        val image = remember(bmp) { bmp.asImageBitmap() }
        Image(
            bitmap = image,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    }
}

@OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun RtspBackdrop(url: String, report: (String) -> Unit) {
    val context = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            val source = RtspMediaSource.Factory()
                .setForceUseRtpTcp(true)
                .setTimeoutMs(8_000)
                .createMediaSource(MediaItem.fromUri(url))
            setMediaSource(source)
            volume = 0f
            playWhenReady = true
            prepare()
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) report("камера поток")
            }

            override fun onPlayerError(error: PlaybackException) {
                report("камера нет сигнала")
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PlayerView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                setShutterBackgroundColor(android.graphics.Color.BLACK)
            }
        },
        update = { it.player = player },
    )
}

@Composable
private fun Dashboard(
    park: ParkUi,
    partner: PartnerUi,
    parkState: String,
    partnerState: String,
    camStatus: String,
    now: ZonedDateTime,
    onOpenSettings: () -> Unit,
) {
    val clock = clockText(now)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { focus.requestFocus() }
    }
    val demo = parkState == "demo" || partnerState == "demo"
    Column(Modifier.fillMaxSize().padding(horizontal = 22.dp, vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Image(
                painter = painterResource(R.drawable.logo_zhitnevo),
                contentDescription = "Житнево Парк",
                modifier = Modifier.size(58.dp),
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Житнево Парк", color = Fg, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    buildString {
                        append("Панель ")
                        append(statusWord(parkState, park.ready))
                        append("   /   Сеть ")
                        append(statusWord(partnerState, partner.ready))
                        if (demo) append("   ·   образец цифр")
                        append("   ·   ")
                        append(camStatus)
                    },
                    color = Muted,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(clock.first, color = Fg, fontSize = 36.sp, fontWeight = FontWeight.SemiBold)
                Text(clock.second, color = Muted, fontSize = 14.sp)
            }
            Spacer(Modifier.width(16.dp))
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier.focusRequester(focus).height(52.dp),
            ) {
                Text("Настройки", color = Fg, fontSize = 16.sp)
            }
        }
        if (!park.ready && park.status.isNotBlank() && parkState != "demo") {
            Text(park.status, color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
        }
        Band(R.drawable.logo_tt, "Транспортные технологии", Modifier.padding(top = 12.dp).weight(1f)) {
            MetricGrid(parkMetrics(park), columns = 4, partner = false, Modifier.fillMaxSize())
        }
        if (!partner.ready && partner.status.isNotBlank() && partnerState != "demo") {
            Text(partner.status, color = Partner, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Band(R.drawable.logo_dornet, "Дорожная сеть", Modifier.padding(top = 10.dp).weight(1.35f)) {
            MetricGrid(partnerMetrics(partner), columns = 4, partner = true, Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun Band(logo: Int, caption: String, modifier: Modifier, content: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(92.dp)) {
            Image(
                painter = painterResource(logo),
                contentDescription = caption,
                modifier = Modifier.size(72.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f).fillMaxHeight()) { content() }
    }
}

@Composable
private fun MetricGrid(items: List<Metric>, columns: Int, partner: Boolean, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items.chunked(columns).forEach { row ->
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { metric ->
                    MetricCard(metric, partner, Modifier.weight(1f).fillMaxHeight())
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun MetricCard(metric: Metric, partner: Boolean, modifier: Modifier) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(CardBg)
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(metric.label, color = Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            metric.value,
            color = if (partner) Partner else Fg,
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
        if (!metric.detail.isNullOrBlank()) {
            Text(
                metric.detail,
                color = Money,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

private fun statusWord(state: String, ready: Boolean): String = when {
    state == "demo" -> "демо"
    state == "loading" -> "связь…"
    state == "error" || !ready -> "нет связи"
    else -> "на связи"
}

@Composable
private fun SettingsScreen(initial: AppSettings, onClose: () -> Unit, onSave: (AppSettings) -> Unit) {
    var server by remember { mutableStateOf(initial.server) }
    var username by remember { mutableStateOf(initial.username) }
    var password by remember { mutableStateOf(initial.password) }
    var partnerUser by remember { mutableStateOf(initial.partnerUser) }
    var partnerPassword by remember { mutableStateOf(initial.partnerPassword) }
    var hikHost by remember { mutableStateOf(initial.hikHost) }
    var hikHttp by remember { mutableStateOf(initial.hikHttpPort.toString()) }
    var hikRtsp by remember { mutableStateOf(initial.hikRtspPort.toString()) }
    var hikUser by remember { mutableStateOf(initial.hikUser) }
    var hikPassword by remember { mutableStateOf(initial.hikPassword) }
    var hikChannel by remember { mutableStateOf(initial.hikChannel.toString()) }
    var hikHttps by remember { mutableStateOf(initial.hikHttps) }
    var hikLive by remember { mutableStateOf(initial.hikLive) }
    var hikSub by remember { mutableStateOf(initial.hikSubstream) }
    var dim by remember { mutableStateOf(initial.dim) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xF20B0C0E))
            .verticalScroll(rememberScrollState())
            .padding(28.dp),
    ) {
        Text("Вход и камера", color = Fg, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Панель Житнево и Дорожная сеть — те же цифры, что у бегущей строки. Камера Hikvision в локальной сети телевизора: снимок ISAPI или поток RTSP. Пароли остаются только на этом устройстве.",
            color = Muted,
            fontSize = 15.sp,
            modifier = Modifier.padding(top = 6.dp, bottom = 16.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Панель", color = Fg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Field("Адрес панели", server, { server = it })
                Field("Логин панели", username, { username = it })
                Field("Пароль панели", password, { password = it }, secret = true)
                Spacer(Modifier.height(8.dp))
                Text("Дорожная сеть", color = Partner, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Field("Логин", partnerUser, { partnerUser = it })
                Field("Пароль", partnerPassword, { partnerPassword = it }, secret = true)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Камера Hikvision", color = Fg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Field("IP-адрес", hikHost, { hikHost = it })
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) { Field("HTTP-порт", hikHttp, { hikHttp = it }, KeyboardType.Number) }
                    Box(Modifier.weight(1f)) { Field("RTSP-порт", hikRtsp, { hikRtsp = it }, KeyboardType.Number) }
                    Box(Modifier.weight(1f)) { Field("Канал", hikChannel, { hikChannel = it }, KeyboardType.Number) }
                }
                Field("Логин камеры", hikUser, { hikUser = it })
                Field("Пароль камеры", hikPassword, { hikPassword = it }, secret = true)
                Toggle("HTTPS (обычно порт 443)", hikHttps) { hikHttps = it }
                Toggle("Живой поток RTSP вместо снимка", hikLive) { hikLive = it }
                Toggle("Субпоток (канал x02, обычно H.264)", hikSub) { hikSub = it }
                Text(
                    if (hikLive) {
                        "Поток: rtsp://адрес:554/Streaming/Channels/102 по TCP. Основной поток Hikvision часто H.265 — плеер телевизора его не откроет, оставьте субпоток."
                    } else {
                        "Снимок ISAPI /ISAPI/Streaming/channels/102/picture, вход Digest, обновление каждые 4 секунды. Телевизор и камера должны быть в одной сети."
                    },
                    color = Muted,
                    fontSize = 13.sp,
                )
                Text("Затемнение  ${kotlin.math.round(dim * 100).toInt()}%", color = Muted, fontSize = 14.sp)
                Slider(
                    value = dim,
                    onValueChange = { dim = it },
                    valueRange = 0.12f..0.72f,
                )
            }
        }
        Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    onSave(
                        initial.copy(
                            server = server,
                            username = username,
                            password = password,
                            partnerUser = partnerUser,
                            partnerPassword = partnerPassword,
                            hikHost = hikHost,
                            hikHttpPort = hikHttp.toIntOrNull() ?: 80,
                            hikRtspPort = hikRtsp.toIntOrNull() ?: 554,
                            hikUser = hikUser,
                            hikPassword = hikPassword,
                            hikChannel = hikChannel.toIntOrNull() ?: 1,
                            hikHttps = hikHttps,
                            hikLive = hikLive,
                            hikSubstream = hikSub,
                            dim = dim,
                        ),
                    )
                },
                colors = ButtonDefaults.buttonColors(containerColor = Fg, contentColor = Ink),
                modifier = Modifier.height(52.dp),
            ) {
                Text("Сохранить", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            OutlinedButton(onClick = onClose, modifier = Modifier.height(52.dp)) {
                Text("Закрыть", color = Fg, fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    keyboard: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        label = { Text(label) },
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        textStyle = TextStyle(color = Fg, fontSize = 18.sp),
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Fg,
            unfocusedTextColor = Fg,
            focusedBorderColor = Partner,
            unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
            focusedLabelColor = Muted,
            unfocusedLabelColor = Muted,
            cursorColor = Fg,
        ),
    )
}

@Composable
private fun Toggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = Fg, fontSize = 15.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
