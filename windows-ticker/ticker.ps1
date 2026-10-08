#requires -Version 5.1
# Zhitnevo Park ticker. Double-click ZhitnevoStroka.bat
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
Add-Type -AssemblyName System.Security

[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
# The certificate is issued to the server IP. Trust it for this app only.
[System.Net.ServicePointManager]::ServerCertificateValidationCallback = { param($sender, $cert, $chain, $errors) $true }

$ErrorActionPreference = "Stop"
$AppDir = Join-Path $env:APPDATA "ZhitnevoTicker"
New-Item -ItemType Directory -Force -Path $AppDir | Out-Null
$ConfigPath = Join-Path $AppDir "config.json"
$LogoPath = Join-Path $AppDir "logo.png"

$script:cfg = $null
$script:token = ""
$script:role = ""
$script:stats = $null
$script:offline = $false
$script:status = "Подключение…"
$script:scroll = 0.0
$script:truck = 0.0
$script:distance = 0.0
$script:wheel = 0.0
$script:busy = $false
$script:pieces = @()
$script:strip = $null
$script:stripWidth = 1
$script:logo = $null
$script:clock = [Diagnostics.Stopwatch]::StartNew()
$script:lastTick = 0

function Protect-Text([string]$plain) {
    if ([string]::IsNullOrEmpty($plain)) { return "" }
    $bytes = [Text.Encoding]::UTF8.GetBytes($plain)
    $prot = [Security.Cryptography.ProtectedData]::Protect($bytes, $null, "CurrentUser")
    return [Convert]::ToBase64String($prot)
}
function Unprotect-Text([string]$stored) {
    if ([string]::IsNullOrEmpty($stored)) { return "" }
    try {
        $prot = [Convert]::FromBase64String($stored)
        $bytes = [Security.Cryptography.ProtectedData]::Unprotect($prot, $null, "CurrentUser")
        return [Text.Encoding]::UTF8.GetString($bytes)
    } catch { return "" }
}
function Read-Config {
    if (-not (Test-Path $ConfigPath)) { return $null }
    try { return Get-Content $ConfigPath -Raw -Encoding UTF8 | ConvertFrom-Json } catch { return $null }
}
function Save-Config($cfg) {
    $cfg | ConvertTo-Json | Set-Content $ConfigPath -Encoding UTF8
}
function Ensure-Logo {
    if (Test-Path $LogoPath) {
        try { return [Drawing.Image]::FromFile($LogoPath) } catch {}
    }
    $bytes = [Convert]::FromBase64String("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")
    [IO.File]::WriteAllBytes($LogoPath, $bytes)
    return [Drawing.Image]::FromFile($LogoPath)
}

function Invoke-Api([string]$method, [string]$path, $bodyObj) {
    $base = $script:cfg.server.TrimEnd("/")
    $req = [System.Net.HttpWebRequest]::Create($base + $path)
    $req.Method = $method
    $req.Timeout = 12000
    $req.Accept = "application/json"
    if ($script:token) { $req.Headers["Authorization"] = "Bearer $($script:token)" }
    if ($null -ne $bodyObj) {
        $bytes = [Text.Encoding]::UTF8.GetBytes(($bodyObj | ConvertTo-Json -Compress))
        $req.ContentType = "application/json; charset=utf-8"
        $req.ContentLength = $bytes.Length
        $stream = $req.GetRequestStream()
        $stream.Write($bytes, 0, $bytes.Length)
        $stream.Close()
    }
    try {
        $res = $req.GetResponse()
        $reader = New-Object IO.StreamReader($res.GetResponseStream(), [Text.Encoding]::UTF8)
        $text = $reader.ReadToEnd()
        $reader.Close()
        $res.Close()
        if ([string]::IsNullOrWhiteSpace($text)) { return $null }
        return $text | ConvertFrom-Json
    } catch [System.Net.WebException] {
        $code = 0
        $msg = $_.Exception.Message
        $errRes = $_.Exception.Response
        if ($errRes) {
            $code = [int]$errRes.StatusCode
            try {
                $reader = New-Object IO.StreamReader($errRes.GetResponseStream(), [Text.Encoding]::UTF8)
                $errText = $reader.ReadToEnd()
                $reader.Close()
                $parsed = $errText | ConvertFrom-Json
                if ($parsed.error) { $msg = [string]$parsed.error }
            } catch {}
        }
        throw "$code $msg"
    }
}
function Connect-Server {
    $pass = Unprotect-Text $script:cfg.secret
    $login = Invoke-Api "POST" "/api/login" @{ username = $script:cfg.username; password = $pass }
    $script:token = [string]$login.token
    $script:role = [string]$login.role
    $script:cfg.token = $script:token
    $script:cfg.role = $script:role
    Save-Config $script:cfg
}
function Update-Stats {
    if ($script:busy) { return }
    $script:busy = $true
    try {
        if (-not $script:token) { Connect-Server }
        try {
            $script:stats = Invoke-Api "GET" "/api/stats" $null
        } catch {
            if ($_.Exception.Message -match "401|Нужно войти") {
                Connect-Server
                $script:stats = Invoke-Api "GET" "/api/stats" $null
            } else { throw }
        }
        $script:offline = $false
        $script:status = ""
        Build-Pieces
    } catch {
        $script:offline = $true
        $script:status = [string]$_.Exception.Message
        if (-not $script:status) { $script:status = "Нет связи с сервером" }
        Build-Pieces
    } finally {
        $script:busy = $false
    }
}

function Noun([int]$n, [string]$one, [string]$few, [string]$many) {
    $a = [Math]::Abs($n) % 100
    $b = $a % 10
    if ($a -gt 10 -and $a -lt 20) { return $many }
    if ($b -eq 1) { return $one }
    if ($b -ge 2 -and $b -le 4) { return $few }
    return $many
}
function Money([int]$n) {
    return ("{0:N0}" -f $n) + " ₽"
}
function Add-Piece([System.Collections.Generic.List[object]]$list, [string]$text, [Drawing.Color]$color, [Drawing.Font]$font) {
    $list.Add([pscustomobject]@{ Text = $text; Color = $color; Font = $font }) | Out-Null
}
function Build-Pieces {
    $fg = [Drawing.Color]::FromArgb(241, 242, 244)
    $muted = [Drawing.Color]::FromArgb(154, 160, 170)
    $ok = [Drawing.Color]::FromArgb(125, 186, 138)
    $bad = [Drawing.Color]::FromArgb(226, 59, 59)
    $gold = [Drawing.Color]::FromArgb(224, 161, 90)
    $main = New-Object Drawing.Font "Segoe UI", 18, ([Drawing.FontStyle]::Bold), ([Drawing.GraphicsUnit]::Pixel)
    $soft = New-Object Drawing.Font "Segoe UI", 15, ([Drawing.FontStyle]::Regular), ([Drawing.GraphicsUnit]::Pixel)
    $list = New-Object System.Collections.Generic.List[object]
    $gap = "      "
    if ($script:offline -and -not $script:stats) {
        Add-Piece $list "Житнево Парк" $fg $main
        Add-Piece $list $gap $muted $soft
        Add-Piece $list $(if ($script:status) { $script:status } else { "Нет связи с сервером" }) $bad $main
    } else {
        $s = $script:stats
        $months = @("январь","февраль","март","апрель","май","июнь","июль","август","сентябрь","октябрь","ноябрь","декабрь")
        $monthName = $months[[Math]::Max(0, [int]$s.monthNumber - 1)]
        Add-Piece $list "Житнево Парк" $fg $main
        Add-Piece $list $gap $muted $soft
        Add-Piece $list "сегодня" $muted $soft
        Add-Piece $list ("  {0} {1}" -f [int]$s.day, (Noun $s.day "машина" "машины" "машин")) $fg $main
        if ($null -ne $s.dayMoney) {
            Add-Piece $list ("   " + (Money $s.dayMoney)) $ok $main
        }
        Add-Piece $list $gap $muted $soft
        $unauthColor = $(if ([int]$s.dayUnauthorized -gt 0) { $bad } else { $muted })
        Add-Piece $list "несогласованных" $muted $soft
        Add-Piece $list ("  " + [int]$s.dayUnauthorized) $unauthColor $main
        Add-Piece $list $gap $muted $soft
        Add-Piece $list $monthName $muted $soft
        Add-Piece $list ("  {0} {1}" -f [int]$s.month, (Noun $s.month "машина" "машины" "машин")) $fg $main
        if ($null -ne $s.monthMoney) {
            Add-Piece $list ("   " + (Money $s.monthMoney)) $ok $main
        }
        if ($script:offline) {
            Add-Piece $list $gap $muted $soft
            Add-Piece $list "нет связи, показаны последние цифры" $gold $soft
        }
    }
    $copy = New-Object object[] $list.Count
    for ($i = 0; $i -lt $list.Count; $i++) { $copy[$i] = $list[$i] }
    $script:pieces = $copy
    Update-Strip
}

function Update-Strip {
    if ($script:strip) { $script:strip.Dispose(); $script:strip = $null }
    $items = $script:pieces
    if ($null -eq $items -or $items.Count -eq 0) { $script:stripWidth = 1; return }
    $probe = New-Object Drawing.Bitmap 8, 8
    $pg = [Drawing.Graphics]::FromImage($probe)
    $pg.TextRenderingHint = "AntiAliasGridFit"
    $width = 24.0
    for ($i = 0; $i -lt $items.Count; $i++) {
        $p = $items[$i]
        $width += $pg.MeasureString([string]$p.Text, $p.Font).Width
    }
    $pg.Dispose(); $probe.Dispose()
    $bmpW = [int][Math]::Ceiling($width) + 120
    $bmp = New-Object Drawing.Bitmap $bmpW, 40
    $g = [Drawing.Graphics]::FromImage($bmp)
    $g.TextRenderingHint = "AntiAliasGridFit"
    $g.Clear([Drawing.Color]::Transparent)
    $x = 0.0
    for ($i = 0; $i -lt $items.Count; $i++) {
        $p = $items[$i]
        $brush = New-Object Drawing.SolidBrush $p.Color
        $g.DrawString([string]$p.Text, $p.Font, $brush, $x, 6)
        $x += $g.MeasureString([string]$p.Text, $p.Font).Width
        $brush.Dispose()
    }
    $g.Dispose()
    $script:strip = $bmp
    $script:stripWidth = $bmp.Width
}

function Set-Autostart([bool]$on) {
    $startup = [Environment]::GetFolderPath("Startup")
    $lnk = Join-Path $startup "Житнево Строка.lnk"
    if (-not $on) {
        if (Test-Path $lnk) { Remove-Item $lnk -Force }
        return
    }
    $bat = Join-Path $PSScriptRoot "ZhitnevoStroka.bat"
    $shell = New-Object -ComObject WScript.Shell
    $sc = $shell.CreateShortcut($lnk)
    $sc.TargetPath = $bat
    $sc.WorkingDirectory = $PSScriptRoot
    $sc.WindowStyle = 7
    $sc.Description = "Житнево Парк — бегущая строка"
    $sc.Save()
}

function Show-Setup {
    $existing = Read-Config
    $form = New-Object Windows.Forms.Form
    $form.Text = "Житнево Парк — строка"
    $form.StartPosition = "CenterScreen"
    $form.FormBorderStyle = "FixedDialog"
    $form.MaximizeBox = $false
    $form.MinimizeBox = $false
    $form.ClientSize = New-Object Drawing.Size 420, 280
    $form.BackColor = [Drawing.Color]::FromArgb(20, 22, 26)
    $form.ForeColor = [Drawing.Color]::FromArgb(241, 242, 244)
    $form.Font = New-Object Drawing.Font "Segoe UI", 10

    function Add-Label([string]$text, [int]$y) {
        $l = New-Object Windows.Forms.Label
        $l.Text = $text
        $l.AutoSize = $true
        $l.Location = New-Object Drawing.Point 24, $y
        $l.ForeColor = [Drawing.Color]::FromArgb(154, 160, 170)
        $form.Controls.Add($l)
    }
    function Add-Box([string]$value, [int]$y, [bool]$secret) {
        $b = New-Object Windows.Forms.TextBox
        $b.Text = $value
        $b.Location = New-Object Drawing.Point 24, $y
        $b.Size = New-Object Drawing.Size 372, 28
        $b.BorderStyle = "FixedSingle"
        $b.BackColor = [Drawing.Color]::FromArgb(28, 31, 37)
        $b.ForeColor = [Drawing.Color]::FromArgb(241, 242, 244)
        if ($secret) { $b.UseSystemPasswordChar = $true }
        $form.Controls.Add($b)
        return $b
    }
    Add-Label "Адрес панели" 16
    $server = Add-Box $(if ($existing.server) { $existing.server } else { "https://168.113.210.66" }) 38 $false
    Add-Label "Логин" 74
    $user = Add-Box $(if ($existing.username) { $existing.username } else { "" }) 96 $false
    Add-Label "Пароль" 132
    $pass = Add-Box "" 154 $true
    $auto = New-Object Windows.Forms.CheckBox
    $auto.Text = "Запускать вместе с Windows"
    $auto.AutoSize = $true
    $auto.Location = New-Object Drawing.Point 24, 196
    $auto.ForeColor = [Drawing.Color]::FromArgb(241, 242, 244)
    $auto.Checked = [bool]$existing.autostart
    $form.Controls.Add($auto)
    $ok = New-Object Windows.Forms.Button
    $ok.Text = "Сохранить"
    $ok.Location = New-Object Drawing.Point 24, 230
    $ok.Size = New-Object Drawing.Size 160, 34
    $ok.FlatStyle = "Flat"
    $ok.BackColor = [Drawing.Color]::FromArgb(232, 234, 238)
    $ok.ForeColor = [Drawing.Color]::FromArgb(16, 17, 20)
    $form.Controls.Add($ok)
    $form.AcceptButton = $ok
    $ok.Add_Click({
        if (-not $user.Text.Trim() -or -not $pass.Text) {
            [Windows.Forms.MessageBox]::Show("Нужны логин и пароль панели.", "Житнево Парк")
            return
        }
        $script:cfg = [pscustomobject]@{
            server = $server.Text.Trim().TrimEnd("/")
            username = $user.Text.Trim()
            secret = (Protect-Text $pass.Text)
            token = ""
            role = ""
            autostart = [bool]$auto.Checked
        }
        $script:token = ""
        try {
            Connect-Server
            Set-Autostart $auto.Checked
            $form.DialogResult = "OK"
            $form.Close()
        } catch {
            [Windows.Forms.MessageBox]::Show([string]$_.Exception.Message, "Не удалось войти")
        }
    })
    $result = $form.ShowDialog()
    $form.Dispose()
    return $result -eq "OK"
}

$script:cfg = Read-Config
if (-not $script:cfg -or -not $script:cfg.username -or -not $script:cfg.secret) {
    if (-not (Show-Setup)) { return }
}
$script:token = [string]$script:cfg.token
$script:role = [string]$script:cfg.role
try { $script:logo = Ensure-Logo } catch { $script:logo = $null }

$area = [Windows.Forms.Screen]::PrimaryScreen.WorkingArea
$barH = 58
$form = New-Object Windows.Forms.Form
$form.FormBorderStyle = "None"
$form.ShowInTaskbar = $true
$form.TopMost = $true
$form.StartPosition = "Manual"
$form.Bounds = New-Object Drawing.Rectangle $area.X, ($area.Bottom - $barH), $area.Width, $barH
$form.BackColor = [Drawing.Color]::FromArgb(20, 22, 26)
$form.Text = "Житнево Парк"
$form.GetType().GetProperty("DoubleBuffered", [Reflection.BindingFlags]"Instance,NonPublic").SetValue($form, $true, $null)

$script:cardBrush = New-Object Drawing.SolidBrush ([Drawing.Color]::FromArgb(20, 22, 26))
$script:linePen = New-Object Drawing.Pen ([Drawing.Color]::FromArgb(36, 241, 242, 244)), 1
$script:closeBrush = New-Object Drawing.SolidBrush ([Drawing.Color]::FromArgb(154, 160, 170))
$script:closeFont = New-Object Drawing.Font "Segoe UI", 16, ([Drawing.FontStyle]::Regular), ([Drawing.GraphicsUnit]::Pixel)

$menu = New-Object Windows.Forms.ContextMenuStrip
$miRefresh = $menu.Items.Add("Обновить")
$miSetup = $menu.Items.Add("Сменить вход")
$miTop = $menu.Items.Add("Поверх всех окон")
$miTop.Checked = $true
$miExit = $menu.Items.Add("Закрыть")
$form.ContextMenuStrip = $menu
$miRefresh.Add_Click({ Update-Stats })
$miSetup.Add_Click({ if (Show-Setup) { Update-Stats } })
$miTop.Add_Click({ $miTop.Checked = -not $miTop.Checked; $form.TopMost = $miTop.Checked })
$miExit.Add_Click({ $form.Close() })

$drag = $false
$down = [Drawing.Point]::Empty
$form.Add_MouseDown({
    if ($_.Button -eq "Left" -and $_.X -lt ($form.ClientSize.Width - 36)) { $script:drag = $true; $script:down = $_.Location }
})
$form.Add_MouseMove({
    if ($script:drag) {
        $form.Left += $_.X - $script:down.X
        $form.Top += $_.Y - $script:down.Y
    }
})
$form.Add_MouseUp({ $script:drag = $false })

$form.Add_Paint({
    $g = $_.Graphics
    $g.SmoothingMode = "AntiAlias"
    $g.PixelOffsetMode = "HighQuality"
    $g.TextRenderingHint = "AntiAliasGridFit"
    $w = [single]$form.ClientSize.Width
    $h = [single]$form.ClientSize.Height
    $g.FillRectangle($script:cardBrush, 0, 0, $w, $h)
    $g.DrawLine($script:linePen, 0, 0, $w, 0)
    $g.DrawLine($script:linePen, 72, 12, 72, ($h - 12))
    if ($script:logo) { $g.DrawImage($script:logo, 12, 6, 48, 46) }

    $clip = New-Object Drawing.Rectangle 84, 0, ([int]$w - 124), ([int]$h)
    $g.SetClip($clip)
    if ($script:strip -and $script:stripWidth -gt 1) {
        $offset = [single]($script:scroll % $script:stripWidth)
        $left = New-Object Drawing.RectangleF
        $left.X = [single](84 - $offset)
        $left.Y = 8
        $left.Width = $script:strip.Width
        $left.Height = $script:strip.Height
        $g.DrawImage($script:strip, $left)
        $right = New-Object Drawing.RectangleF
        $right.X = [single](84 - $offset + $script:stripWidth)
        $right.Y = 8
        $right.Width = $script:strip.Width
        $right.Height = $script:strip.Height
        $g.DrawImage($script:strip, $right)
    }
    $g.ResetClip()
    $g.DrawString("×", $script:closeFont, $script:closeBrush, ($w - 26), 16)
})
$form.Add_MouseClick({
    if ($_.Button -eq "Left" -and $_.X -gt ($form.ClientSize.Width - 36) -and $_.Y -lt 36) { $form.Close() }
})

$timer = New-Object Windows.Forms.Timer
$timer.Interval = 16
$timer.Add_Tick({
    $now = $script:clock.ElapsedMilliseconds
    $dt = [Math]::Min(0.05, (($now - $script:lastTick) / 1000.0))
    $script:lastTick = $now
    if ($script:stripWidth -gt 1) {
        $script:scroll = ($script:scroll + (46 * $dt)) % $script:stripWidth
    }
    $form.Invalidate()
})
$timer.Start()

$poll = New-Object Windows.Forms.Timer
$poll.Interval = 45000
$poll.Add_Tick({ Update-Stats })
$poll.Start()

$form.Add_Shown({ Update-Stats })
[void]$form.ShowDialog()
$timer.Stop(); $poll.Stop()
if ($script:logo) { $script:logo.Dispose() }
if ($script:strip) { $script:strip.Dispose() }
$script:cardBrush.Dispose(); $script:linePen.Dispose(); $script:closeBrush.Dispose(); $script:closeFont.Dispose()
