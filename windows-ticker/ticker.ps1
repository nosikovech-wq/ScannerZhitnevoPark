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
$script:partner = $null
$script:partnerToken = ""
$script:partnerAt = [datetime]::MinValue
$script:partnerLogo = $null
$script:clock = [Diagnostics.Stopwatch]::StartNew()
$script:lastTick = 0
$script:slide = 0
$script:slideY = 0.0
$script:sliding = $false
$script:slideHold = Get-Date

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
    $beside = Join-Path $PSScriptRoot "logo.png"
    if (Test-Path $beside) { return [Drawing.Image]::FromFile($beside) }
    if (Test-Path $LogoPath) { return [Drawing.Image]::FromFile($LogoPath) }
    return $null
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
        Build-View
    } catch {
        $script:offline = $true
        $script:status = [string]$_.Exception.Message
        if (-not $script:status) { $script:status = "Нет связи с сервером" }
        Build-View
    } finally {
        $script:busy = $false
        if ($script:form) { $script:form.Invalidate() }
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
function Money-Pair([int]$a, [int]$b) {
    return ("{0} · {1}" -f (Money $a), (Money $b))
}
function Format-Average([int]$count, [int]$day) {
    if ($day -lt 1) { $day = 1 }
    $value = [math]::Round(([double]$count) / $day, 1)
    $text = $value.ToString("0.#", [Globalization.CultureInfo]::GetCultureInfo("ru-RU"))
    return "$text в день"
}
function Invoke-Partner([string]$method, [string]$url, $bodyObj, [string]$bearer) {
    $req = [System.Net.HttpWebRequest]::Create($url)
    $req.Method = $method
    $req.Timeout = 20000
    $req.Accept = "application/json"
    if ($bearer) { $req.Headers["Authorization"] = "Bearer $bearer" }
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

function Get-ServiceCounts([string]$from, [string]$to, [string]$token) {
    $shower = 0
    $laundry = 0
    $start = 0
    $total = 1
    while ($start -lt $total -and $start -lt 800) {
        $page = Invoke-Partner "GET" "https://back.dornet.ru/api/supplier/order/list?limit=200&start=$start&dateFrom=$from&dateTo=$to&pointType=d" $null $token
        $total = [int]$page.total
        $items = $page.items
        if ($null -eq $items) { break }
        if ($items -is [System.Array]) {
            for ($i = 0; $i -lt $items.Length; $i++) {
                $kind = [string]$items[$i].orderType
                if ($kind -eq "Душ") { $shower++ }
                elseif ($kind -eq "Прачечная") { $laundry++ }
            }
            if ($items.Length -eq 0) { break }
        } else {
            $kind = [string]$items.orderType
            if ($kind -eq "Душ") { $shower++ }
            elseif ($kind -eq "Прачечная") { $laundry++ }
            break
        }
        $start += 200
    }
    return @{ Shower = $shower; Laundry = $laundry }
}

function Fetch-PartnerData([string]$token, [string]$today, [string]$tomorrow, [string]$monthStart, [string]$nextMonth) {
    $dayPark = Invoke-Partner "GET" "https://back.dornet.ru/api/supplier/order/list?limit=1&start=0&dateFrom=$today&dateTo=$tomorrow&pointType=p" $null $token
    $monthPark = Invoke-Partner "GET" "https://back.dornet.ru/api/supplier/order/list?limit=1&start=0&dateFrom=$monthStart&dateTo=$nextMonth&pointType=p" $null $token
    $daySvc = Get-ServiceCounts $today $tomorrow $token
    $monthSvc = Get-ServiceCounts $monthStart $nextMonth $token
    $reviews = Invoke-Partner "GET" "https://back.dornet.ru/api/supplier/review/count" $null $token
    $reviewStart = 0
    if ($reviews.count) { $reviewStart = [Math]::Max(0, ([int]$reviews.count - 1)) }
    $latest = Invoke-Partner "GET" "https://back.dornet.ru/api/supplier/review/list?limit=1&start=$reviewStart" $null $token
    return [pscustomobject]@{ DayPark = $dayPark; MonthPark = $monthPark; DaySvc = $daySvc; MonthSvc = $monthSvc; Reviews = $reviews; Latest = $latest }
}
function Update-Partner {
    if (-not $script:cfg.partnerUser -or -not $script:cfg.partnerSecret) {
        $script:partner = [pscustomobject]@{ Ready = $false; Status = "Нет входа Дорожной сети" }
        return
    }
    if ($script:partner -and $script:partner.Ready -and ((Get-Date) - $script:partnerAt).TotalSeconds -lt 600) { return }
    try {
        $pass = Unprotect-Text $script:cfg.partnerSecret
        if (-not $script:partnerToken) {
            $login = Invoke-Partner "POST" "https://back.dornet.ru/api/account/auth" @{ login = [string]$script:cfg.partnerUser; password = $pass } ""
            $script:partnerToken = [string]$login.token
        }
        $tz = [TimeZoneInfo]::FindSystemTimeZoneById("Russian Standard Time")
        $now = [TimeZoneInfo]::ConvertTime([DateTime]::UtcNow, $tz)
        $today = $now.ToString("yyyy-MM-dd")
        $tomorrow = $now.AddDays(1).ToString("yyyy-MM-dd")
        $monthStart = (Get-Date -Year $now.Year -Month $now.Month -Day 1).ToString("yyyy-MM-dd")
        $nextMonth = (Get-Date -Year $now.Year -Month $now.Month -Day 1).AddMonths(1).ToString("yyyy-MM-dd")
        try {
            $data = Fetch-PartnerData $script:partnerToken $today $tomorrow $monthStart $nextMonth
        } catch {
            if ($_.Exception.Message -match "^401") {
                $script:partnerToken = ""
                $login = Invoke-Partner "POST" "https://back.dornet.ru/api/account/auth" @{ login = [string]$script:cfg.partnerUser; password = $pass } ""
                $script:partnerToken = [string]$login.token
                $data = Fetch-PartnerData $script:partnerToken $today $tomorrow $monthStart $nextMonth
            } else { throw }
        }
        $dayCount = [int]$data.DayPark.total
        $monthCount = [int]$data.MonthPark.total
        $showerDay = [int]$data.DaySvc.Shower
        $showerMonth = [int]$data.MonthSvc.Shower
        $laundryDay = [int]$data.DaySvc.Laundry
        $laundryMonth = [int]$data.MonthSvc.Laundry
        $parkDayMoney = $dayCount * 350
        $parkMonthMoney = $monthCount * 350
        $showerDayMoney = $showerDay * 180
        $showerMonthMoney = $showerMonth * 180
        $laundryDayMoney = $laundryDay * 180
        $laundryMonthMoney = $laundryMonth * 180
        $reviewDate = "нет"
        $latest = $null
        if ($null -ne $data.Latest.items) {
            if ($data.Latest.items -is [System.Array]) {
                if ($data.Latest.items.Length -gt 0) { $latest = $data.Latest.items[0] }
            } else { $latest = $data.Latest.items }
        }
        if ($latest -and $latest.date_created) {
            $raw = [string]$latest.date_created
            if ($raw.Length -ge 10) { $reviewDate = $raw.Substring(0, 10).Replace("-", ".") }
        }
        $script:partner = [pscustomobject]@{
            Ready = $true
            Today = ("{0} {1}" -f $dayCount, (Noun $dayCount "машина" "машины" "машин"))
            Month = ("{0} {1}" -f $monthCount, (Noun $monthCount "машина" "машины" "машин"))
            Avg = (Format-Average $monthCount $now.Day)
            Shower = ("{0} · {1}" -f $showerDay, $showerMonth)
            Laundry = ("{0} · {1}" -f $laundryDay, $laundryMonth)
            ReviewDate = $reviewDate
            ParkDayMoney = (Money $parkDayMoney)
            ParkMonthMoney = (Money $parkMonthMoney)
            ShowerMoney = (Money-Pair $showerDayMoney $showerMonthMoney)
            LaundryMoney = (Money-Pair $laundryDayMoney $laundryMonthMoney)
            TotalDay = (Money ($parkDayMoney + $showerDayMoney + $laundryDayMoney))
            TotalMonth = (Money ($parkMonthMoney + $showerMonthMoney + $laundryMonthMoney))
            Status = ""
        }
        $script:partnerAt = Get-Date
    } catch {
        $script:partner = [pscustomobject]@{ Ready = $false; Status = "Дорожная сеть недоступна" }
    } finally {
        if ($script:form) { $script:form.Invalidate() }
    }
}

function Ensure-PartnerLogo {
    $path = Join-Path $AppDir "dornet.png"
    if (-not (Test-Path $path)) {
        $req = [System.Net.HttpWebRequest]::Create("https://front.dornet.ru/favicon/android-icon-192x192.png")
        $req.Timeout = 15000
        $res = $req.GetResponse()
        $input = $res.GetResponseStream()
        $output = [IO.File]::Create($path)
        $input.CopyTo($output)
        $output.Close(); $input.Close(); $res.Close()
    }
    return [Drawing.Image]::FromFile($path)
}

function Import-PartnerSeed {
    $seedPath = Join-Path $PSScriptRoot "partner.local.json"
    if (-not (Test-Path $seedPath) -or -not $script:cfg) { return }
    $seed = Get-Content $seedPath -Raw -Encoding UTF8 | ConvertFrom-Json
    if (-not $seed.username -or -not $seed.password) { return }
    $script:cfg = [pscustomobject]@{
        server = [string]$script:cfg.server
        username = [string]$script:cfg.username
        secret = [string]$script:cfg.secret
        token = [string]$script:cfg.token
        role = [string]$script:cfg.role
        autostart = [bool]$script:cfg.autostart
        large = [bool]$script:large
        partnerUser = [string]$seed.username
        partnerSecret = (Protect-Text ([string]$seed.password))
    }
    Save-Config $script:cfg
    Remove-Item $seedPath -Force
}

function Ensure-PartnerAccount {
    if ($script:cfg.partnerUser -and $script:cfg.partnerSecret) { return }
    $script:cfg = [pscustomobject]@{
        server = [string]$script:cfg.server
        username = [string]$script:cfg.username
        secret = [string]$script:cfg.secret
        token = [string]$script:cfg.token
        role = [string]$script:cfg.role
        autostart = [bool]$script:cfg.autostart
        large = [bool]$script:large
        partnerUser = "a8a155f617"
        partnerSecret = (Protect-Text "c1081a1507")
    }
    Save-Config $script:cfg
}

function Build-View {
    $months = @("Январь","Февраль","Март","Апрель","Май","Июнь","Июль","Август","Сентябрь","Октябрь","Ноябрь","Декабрь")
    if (-not $script:stats) {
        $script:view = [pscustomobject]@{
            Ready = $false
            Status = $(if ($script:status) { [string]$script:status } else { "Нет связи с сервером" })
        }
        return
    }
    $s = $script:stats
    $month = $months[[Math]::Max(0, [int]$s.monthNumber - 1)]
    $dayNum = 1
    if ($s.date -and ([string]$s.date).Length -ge 10) { $dayNum = [int]([string]$s.date).Substring(8, 2) }
    $last = "нет"
    $first = $null
    if ($null -ne $s.plates) {
        if ($s.plates -is [System.Array]) {
            if ($s.plates.Length -gt 0) { $first = $s.plates[0] }
        } else { $first = $s.plates }
    }
    if ($first -and $first.time) { $last = [string]$first.time }
    $dayMoney = "—"
    $monthMoney = "—"
    if ($null -ne $s.dayMoney -and "$($s.dayMoney)" -ne "") { $dayMoney = Money ([int]$s.dayMoney) }
    if ($null -ne $s.monthMoney -and "$($s.monthMoney)" -ne "") { $monthMoney = Money ([int]$s.monthMoney) }
    $script:view = [pscustomobject]@{
        Ready = $true
        Day = ("{0} {1}" -f [int]$s.day, (Noun $s.day "машина" "машины" "машин"))
        DayMoney = $dayMoney
        Month = $month
        MonthCount = ("{0} {1}" -f [int]$s.month, (Noun $s.month "машина" "машины" "машин"))
        MonthMoney = $monthMoney
        Avg = (Format-Average ([int]$s.month) $dayNum)
        Last = $last
        Offline = [bool]$script:offline
        Status = [string]$script:status
    }
}

function Fit-Font($g, [string]$text, $font, [single]$maxW) {
    if (-not $text) { return $font }
    $size = $g.MeasureString($text, $font)
    if ($size.Width -le ($maxW + 1)) { return $font }
    $px = [Math]::Floor($font.Size * $maxW / [Math]::Max(1, $size.Width))
    if ($px -ge $font.Size) { return $font }
    if ($px -lt 12) { $px = 12 }
    return New-Object Drawing.Font $font.FontFamily.Name, ([single]$px), $font.Style, ([Drawing.GraphicsUnit]::Pixel)
}
function Draw-Card($g, [int]$x, [int]$y, [int]$w, [int]$h, [string]$label, [string]$value, $valueBrush, $pen) {
    if (-not $pen) { $pen = $script:cardPen }
    if ($w -lt 24 -or $h -lt 24) { return }
    $path = New-Object Drawing.Drawing2D.GraphicsPath
    $d = 14
    if (($w -lt 36) -or ($h -lt 36)) { $d = 8 }
    $path.AddArc($x, $y, $d, $d, 180, 90)
    $path.AddArc(($x + $w - $d), $y, $d, $d, 270, 90)
    $path.AddArc(($x + $w - $d), ($y + $h - $d), $d, $d, 0, 90)
    $path.AddArc($x, ($y + $h - $d), $d, $d, 90, 90)
    $path.CloseFigure()
    $g.FillPath($script:cardFill, $path)
    $g.DrawPath($pen, $path)
    $path.Dispose()
    $state = $g.Save()
    $clipX = [int]($x + 8)
    $clipY = [int]($y + 4)
    $clipW = [int]($w - 16)
    $clipH = [int]($h - 8)
    if ($clipW -lt 8) { $clipW = 8 }
    if ($clipH -lt 8) { $clipH = 8 }
    $g.SetClip((New-Object Drawing.Rectangle -ArgumentList $clipX, $clipY, $clipW, $clipH))
    $maxW = [single]($w - 24)
    if ($script:large) {
        $font = $script:labelFont
        $fmt = New-Object Drawing.StringFormat
        $fmt.Trimming = "EllipsisCharacter"
        $lineH = $font.GetHeight($g)
        $labelBox = $g.MeasureString($label, $font, [int]$maxW)
        $valueBox = $g.MeasureString($value, $font, [int]$maxW)
        $labelH = [Math]::Min([single]$labelBox.Height, ($lineH * 2))
        $valueH = [Math]::Min([single]$valueBox.Height, ($lineH * 2))
        $block = $labelH + 6 + $valueH
        $ty = $y + (($h - $block) / 2)
        if ($ty -lt ($y + 6)) { $ty = $y + 6 }
        $labelRect = New-Object Drawing.RectangleF -ArgumentList ([single]($x + 12)), ([single]$ty), $maxW, $labelH
        $valueRect = New-Object Drawing.RectangleF -ArgumentList ([single]($x + 12)), ([single]($ty + $labelH + 6)), $maxW, $valueH
        $g.DrawString($label, $font, $script:mutedBrush, $labelRect, $fmt)
        $g.DrawString($value, $font, $valueBrush, $valueRect, $fmt)
        $fmt.Dispose()
        $g.Restore($state)
        return
    }
    $labelFont = Fit-Font $g $label $script:labelFont $maxW
    $valueFont = Fit-Font $g $value $script:valueFont $maxW
    $lh = $labelFont.GetHeight($g)
    $vh = $valueFont.GetHeight($g)
    $block = $lh + 6 + $vh
    $ty = $y + (($h - $block) / 2)
    if ($ty -lt ($y + 6)) { $ty = $y + 6 }
    $g.DrawString($label, $labelFont, $script:mutedBrush, ($x + 10), $ty)
    $g.DrawString($value, $valueFont, $valueBrush, ($x + 10), ($ty + $lh + 6))
    if (-not [object]::ReferenceEquals($labelFont, $script:labelFont)) { $labelFont.Dispose() }
    if (-not [object]::ReferenceEquals($valueFont, $script:valueFont)) { $valueFont.Dispose() }
    $g.Restore($state)
}

function Draw-Band($g, $cards, [int]$y, [int]$h, $pen, [bool]$partner) {
    $ui = $script:ui
    $n = $cards.Count
    if ($n -lt 1) { return }
    $gap = [int]$ui.Gap
    $avail = [int]$script:form.ClientSize.Width - [int]$ui.Left - [int]$ui.Right - ($gap * ($n - 1))
    $cw = [int]($avail / $n)
    if ($cw -lt 40) { $cw = 40 }
    $x = [int]$ui.Left
    foreach ($card in $cards) {
        $brush = $script:fgBrush
        if ($partner) { $brush = $script:partnerBrush }
        if ($card.Money) { $brush = $script:okBrush }
        Draw-Card $g $x $y $cw $h ([string]$card.L) ([string]$card.V) $brush $pen
        $x += ($cw + $gap)
    }
}
function Draw-Row($g, [single]$top, [int]$mode) {
    $w = [single]$script:form.ClientSize.Width
    $ui = $script:ui
    $gap = $ui.Gap
    $left = $ui.Left
    $rightPad = $ui.Right
    $view = $script:view
    $partner = $script:partner
    $our = New-Object System.Collections.Generic.List[object]
    $ds = New-Object System.Collections.Generic.List[object]
    if (-not $view -or -not $view.Ready) {
        $text = "Подключение…"
        if ($view -and $view.Status) { $text = [string]$view.Status }
        $our.Add([pscustomobject]@{ L = "Житнево Парк"; V = $text; Money = $false })
    } elseif ($mode -eq 0) {
        $dayLabel = "Сегодня"
        if ($view.Offline) { $dayLabel = "Сегодня, нет связи" }
        $our.Add([pscustomobject]@{ L = $dayLabel; V = [string]$view.Day; Money = $false })
        $our.Add([pscustomobject]@{ L = "За месяц"; V = [string]$view.MonthCount; Money = $false })
        $our.Add([pscustomobject]@{ L = "Среднее за день"; V = [string]$view.Avg; Money = $false })
        $our.Add([pscustomobject]@{ L = "Последняя машина"; V = [string]$view.Last; Money = $false })
    } else {
        $our.Add([pscustomobject]@{ L = "Сумма за сегодня"; V = [string]$view.DayMoney; Money = $true })
        $our.Add([pscustomobject]@{ L = "Сумма за месяц"; V = [string]$view.MonthMoney; Money = $true })
    }
    if (-not $partner -or -not $partner.Ready) {
        $note = "Подключение…"
        if ($partner -and $partner.Status) { $note = [string]$partner.Status }
        $ds.Add([pscustomobject]@{ L = "Дорожная сеть"; V = $note; Money = $false })
    } elseif ($mode -eq 0) {
        $ds.Add([pscustomobject]@{ L = "Стоянка сегодня"; V = [string]$partner.Today; Money = $false })
        $ds.Add([pscustomobject]@{ L = "Стоянка за месяц"; V = [string]$partner.Month; Money = $false })
        $ds.Add([pscustomobject]@{ L = "Среднее за день"; V = [string]$partner.Avg; Money = $false })
        $ds.Add([pscustomobject]@{ L = "Душ, сегодня/мес."; V = [string]$partner.Shower; Money = $false })
        $ds.Add([pscustomobject]@{ L = "Прачечная, сегодня/мес."; V = [string]$partner.Laundry; Money = $false })
        $ds.Add([pscustomobject]@{ L = "Последний отзыв"; V = [string]$partner.ReviewDate; Money = $false })
    } else {
        $ds.Add([pscustomobject]@{ L = "Стоянка сегодня"; V = [string]$partner.ParkDayMoney; Money = $true })
        $ds.Add([pscustomobject]@{ L = "Стоянка за месяц"; V = [string]$partner.ParkMonthMoney; Money = $true })
        $ds.Add([pscustomobject]@{ L = "Душ, сегодня/мес."; V = [string]$partner.ShowerMoney; Money = $true })
        $ds.Add([pscustomobject]@{ L = "Прачечная, сегодня/мес."; V = [string]$partner.LaundryMoney; Money = $true })
        $ds.Add([pscustomobject]@{ L = "Всего за день"; V = [string]$partner.TotalDay; Money = $true })
        $ds.Add([pscustomobject]@{ L = "Всего за месяц"; V = [string]$partner.TotalMonth; Money = $true })
    }
    if ($script:large) {
        $y1 = [int]($top + $ui.CardTop)
        $y2 = $y1 + [int]$ui.CardH + [int]$ui.RowGap
        $logo = [int]$ui.Logo
        $ourLogoY = $y1 + [int](($ui.CardH - $logo) / 2)
        $dsLogoY = $y2 + [int](($ui.CardH - $logo) / 2)
        if ($script:logo) { $g.DrawImage($script:logo, 12, $ourLogoY, $logo, $logo) }
        Draw-Band $g $our $y1 ([int]$ui.CardH) $script:cardPen $false
        if ($script:partnerLogo) { $g.DrawImage($script:partnerLogo, 12, $dsLogoY, $logo, $logo) }
        Draw-Band $g $ds $y2 ([int]$ui.CardH) $script:partnerPen $true
        return
    }
    $slots = $our.Count + $ds.Count
    $mid = [int]$ui.Mid
    $gaps = $gap * [Math]::Max(0, $slots - 1)
    $avail = [int]$w - $left - $mid - $rightPad - $gaps
    $cw = [int]($avail / [Math]::Max(1, $slots))
    if ($cw -lt 70) { $cw = 70 }
    $step = $cw + $gap
    $y = [int]($top + $ui.CardTop)
    $x = $left
    foreach ($card in $our) {
        $brush = $script:fgBrush
        if ($card.Money) { $brush = $script:okBrush }
        Draw-Card $g $x $y $cw $ui.CardH ([string]$card.L) ([string]$card.V) $brush $script:cardPen
        $x += $step
    }
    $lineTop = $y + 12
    $lineBot = $y + $ui.CardH - 12
    $g.DrawLine($script:partnerPen, $x, $lineTop, $x, $lineBot)
    $x += 12
    $logoY = $y + [int](($ui.CardH - $ui.Logo) / 2)
    if ($script:partnerLogo) { $g.DrawImage($script:partnerLogo, [int]$x, $logoY, $ui.Logo, $ui.Logo) }
    $x += ($ui.Logo + 12)
    foreach ($card in $ds) {
        $brush = $script:partnerBrush
        if ($card.Money) { $brush = $script:okBrush }
        Draw-Card $g $x $y $cw $ui.CardH ([string]$card.L) ([string]$card.V) $brush $script:partnerPen
        $x += $step
    }
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
    $form.ClientSize = New-Object Drawing.Size 420, 390
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
    Add-Label "Дорожная сеть, логин" 196
    $partnerUser = Add-Box $(if ($existing.partnerUser) { $existing.partnerUser } else { "" }) 218 $false
    Add-Label "Дорожная сеть, пароль" 254
    $partnerPass = Add-Box "" 276 $true
    $auto = New-Object Windows.Forms.CheckBox
    $auto.Text = "Запускать вместе с Windows"
    $auto.AutoSize = $true
    $auto.Location = New-Object Drawing.Point 24, 314
    $auto.ForeColor = [Drawing.Color]::FromArgb(241, 242, 244)
    $auto.Checked = [bool]$existing.autostart
    $form.Controls.Add($auto)
    $ok = New-Object Windows.Forms.Button
    $ok.Text = "Сохранить"
    $ok.Location = New-Object Drawing.Point 24, 344
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
        $keptPartner = ""
        if ($existing -and $existing.partnerSecret) { $keptPartner = [string]$existing.partnerSecret }
        if ($partnerPass.Text) { $keptPartner = Protect-Text $partnerPass.Text }
        $keptUser = $partnerUser.Text.Trim()
        if (-not $keptUser -and $existing -and $existing.partnerUser) { $keptUser = [string]$existing.partnerUser }
        $script:cfg = [pscustomobject]@{
            server = $server.Text.Trim().TrimEnd("/")
            username = $user.Text.Trim()
            secret = (Protect-Text $pass.Text)
            token = ""
            role = ""
            autostart = [bool]$auto.Checked
            large = [bool]$script:large
            partnerUser = $keptUser
            partnerSecret = $keptPartner
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
$script:large = $false
if ($script:cfg -and $script:cfg.large) { $script:large = [bool]$script:cfg.large }
if (-not $script:cfg -or -not $script:cfg.username -or -not $script:cfg.secret) {
    if (-not (Show-Setup)) { return }
}
$script:token = [string]$script:cfg.token
$script:role = [string]$script:cfg.role
Import-PartnerSeed
Ensure-PartnerAccount
try { $script:logo = Ensure-Logo } catch { $script:logo = $null }
try { $script:partnerLogo = Ensure-PartnerLogo } catch { $script:partnerLogo = $null }

$area = [Windows.Forms.Screen]::PrimaryScreen.WorkingArea
$barH = 78
$form = New-Object Windows.Forms.Form
$form.FormBorderStyle = "None"
$form.ShowInTaskbar = $true
$form.TopMost = $true
$form.StartPosition = "Manual"
$form.Bounds = New-Object Drawing.Rectangle $area.X, $area.Y, $area.Width, $barH
$form.BackColor = [Drawing.Color]::FromArgb(20, 22, 26)
$form.Text = "Житнево Парк"
$form.GetType().GetProperty("DoubleBuffered", [Reflection.BindingFlags]"Instance,NonPublic").SetValue($form, $true, $null)

$script:cardBrush = New-Object Drawing.SolidBrush ([Drawing.Color]::FromArgb(11, 12, 14))
$script:cardFill = New-Object Drawing.SolidBrush ([Drawing.Color]::FromArgb(20, 22, 26))
$script:cardPen = New-Object Drawing.Pen ([Drawing.Color]::FromArgb(70, 241, 242, 244)), 1
$script:linePen = New-Object Drawing.Pen ([Drawing.Color]::FromArgb(36, 241, 242, 244)), 1
$script:mutedBrush = New-Object Drawing.SolidBrush ([Drawing.Color]::FromArgb(154, 160, 170))
$script:fgBrush = New-Object Drawing.SolidBrush ([Drawing.Color]::FromArgb(241, 242, 244))
$script:okBrush = New-Object Drawing.SolidBrush ([Drawing.Color]::FromArgb(125, 186, 138))
$script:partnerBrush = New-Object Drawing.SolidBrush ([Drawing.Color]::FromArgb(126, 186, 224))
$script:partnerPen = New-Object Drawing.Pen ([Drawing.Color]::FromArgb(160, 2, 84, 147)), 1.4
$script:badBrush = New-Object Drawing.SolidBrush ([Drawing.Color]::FromArgb(226, 59, 59))
$script:closeBrush = New-Object Drawing.SolidBrush ([Drawing.Color]::FromArgb(154, 160, 170))
$script:form = $form
$script:collapsed = $false

function Update-BarChrome {
    if ($script:large) {
        $logo = 56
        $script:ui = [pscustomobject]@{ Bar = 216; CardH = 96; CardTop = 8; RowGap = 8; Label = 18; Value = 18; Close = 16; Logo = $logo; Left = 84; Mid = (24 + $logo); Right = 76; Gap = 10; Chip = 300 }
    } else {
        $logo = 44
        $script:ui = [pscustomobject]@{ Bar = 78; CardH = 62; CardTop = 8; Label = 13; Value = 16; Close = 15; Logo = $logo; Left = 70; Mid = (24 + $logo); Right = 64; Gap = 8; Chip = 200 }
    }
    if ($script:labelFont) { $script:labelFont.Dispose() }
    if ($script:valueFont) { $script:valueFont.Dispose() }
    if ($script:closeFont) { $script:closeFont.Dispose() }
    $script:labelFont = New-Object Drawing.Font "Segoe UI", $script:ui.Label, ([Drawing.FontStyle]::Regular), ([Drawing.GraphicsUnit]::Pixel)
    $valueStyle = [Drawing.FontStyle]::Bold
    if ($script:large) { $valueStyle = [Drawing.FontStyle]::Regular }
    $script:valueFont = New-Object Drawing.Font "Segoe UI", $script:ui.Value, $valueStyle, ([Drawing.GraphicsUnit]::Pixel)
    $script:closeFont = New-Object Drawing.Font "Segoe UI", $script:ui.Close, ([Drawing.FontStyle]::Regular), ([Drawing.GraphicsUnit]::Pixel)
}
function Apply-BarLayout {
    $screen = [Windows.Forms.Screen]::PrimaryScreen.WorkingArea
    $h = [int]$script:ui.Bar
    $x = [int]$screen.X
    $y = [int]$screen.Y
    if ($script:collapsed) {
        $script:form.Bounds = New-Object Drawing.Rectangle -ArgumentList $x, $y, ([int]$script:ui.Chip), $h
    } else {
        $script:form.Bounds = New-Object Drawing.Rectangle -ArgumentList $x, $y, ([int]$screen.Width), $h
        $script:slideY = 0
        $script:sliding = $false
        $script:slideHold = Get-Date
    }
    $script:form.Invalidate()
}
function Set-BarCollapsed([int]$mode) {
    $script:collapsed = ($mode -eq 1)
    Apply-BarLayout
}
function Set-BarLarge([int]$mode) {
    $script:large = ($mode -eq 1)
    Update-BarChrome
    if ($script:cfg) {
        $script:cfg | Add-Member -NotePropertyName large -NotePropertyValue ([bool]$script:large) -Force
        Save-Config $script:cfg
    }
    Apply-BarLayout
}
function Draw-SideButtons($g, [single]$w, [single]$h) {
    $x0 = $w - [single]$script:ui.Right
    $g.DrawLine($script:linePen, $x0, 8, $x0, ($h - 8))
    $marks = New-Object System.Collections.Generic.List[string]
    $marks.Add("×")
    if ($script:large) { $marks.Add("A") } else { $marks.Add("A+") }
    if ($script:collapsed) { $marks.Add("+") } else { $marks.Add("–") }
    for ($i = 1; $i -lt 3; $i++) {
        $yy = [Math]::Floor($h * $i / 3.0)
        $g.DrawLine($script:linePen, ($x0 + 8), $yy, ($w - 8), $yy)
    }
    for ($i = 0; $i -lt 3; $i++) {
        $top = [Math]::Floor($h * $i / 3.0)
        $bot = [Math]::Floor($h * ($i + 1) / 3.0)
        $text = [string]$marks[$i]
        $size = $g.MeasureString($text, $script:closeFont)
        $tx = $x0 + (([single]$script:ui.Right - $size.Width) / 2)
        $ty = $top + (($bot - $top - $size.Height) / 2)
        $g.DrawString($text, $script:closeFont, $script:closeBrush, $tx, $ty)
    }
}
function Hit-Band([int]$y, [int]$h) {
    if ($h -le 0) { return 0 }
    if ($y -lt 0) { return 0 }
    $band = [Math]::Floor($y * 3.0 / $h)
    if ($band -lt 0) { return 0 }
    if ($band -gt 2) { return 2 }
    return [int]$band
}

$menu = New-Object Windows.Forms.ContextMenuStrip
$miRefresh = $menu.Items.Add("Обновить")
$miSetup = $menu.Items.Add("Сменить вход")
$miTop = $menu.Items.Add("Поверх всех окон")
$miTop.Checked = $true
$miExit = $menu.Items.Add("Закрыть")
$form.ContextMenuStrip = $menu
$miRefresh.Add_Click({ $script:partnerAt = [datetime]::MinValue; Update-Stats; Update-Partner })
$miSetup.Add_Click({ if (Show-Setup) { Update-Stats } })
$miTop.Add_Click({ $miTop.Checked = -not $miTop.Checked; $form.TopMost = $miTop.Checked })
$miExit.Add_Click({ $form.Close() })

$drag = $false
$down = [Drawing.Point]::Empty
$script:clickLock = [datetime]::MinValue
$form.Add_MouseDown({
    if ($_.Button -ne "Left") { return }
    if (((Get-Date) - $script:clickLock).TotalMilliseconds -lt 400) { return }
    $edge = $form.ClientSize.Width - [int]$script:ui.Right
    if ($_.X -ge $edge) {
        $band = Hit-Band $_.Y $form.ClientSize.Height
        $script:clickLock = Get-Date
        if ($band -eq 0) { $form.Close(); return }
        if ($band -eq 1) {
            if ($script:large) { Set-BarLarge 0 } else { Set-BarLarge 1 }
            return
        }
        if ($script:collapsed) { Set-BarCollapsed 0 } else { Set-BarCollapsed 1 }
        return
    }
    if ($script:collapsed) {
        $script:clickLock = Get-Date
        Set-BarCollapsed 0
        return
    }
    $script:drag = $true
    $script:down = $_.Location
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
    $g.DrawLine($script:linePen, 0, ($h - 1), $w, ($h - 1))
    if ($script:collapsed) {
        $logo = [int]$script:ui.Logo
        $ly = [int](($h - $logo) / 2)
        if ($script:logo) { $g.DrawImage($script:logo, 14, $ly, $logo, $logo) }
        $g.DrawString("Житнево", $script:valueFont, $script:fgBrush, ($logo + 24), [int](($h / 2) - 12))
        Draw-SideButtons $g $w $h
        return
    }
    $state = $g.Save()
    $clipW = [int]$w - [int]$script:ui.Right
    $clipH = [int]$h
    $g.SetClip((New-Object Drawing.Rectangle -ArgumentList 0, 0, $clipW, $clipH))
    $shift = [single]$script:slideY
    $current = [int]$script:slide
    $next = 1 - $current
    Draw-Row $g $shift $current
    Draw-Row $g ($shift - $h) $next
    $g.Restore($state)
    if (-not $script:large) {
        $logo = [int]$script:ui.Logo
        $ly = [int](($h - $logo) / 2)
        if ($script:logo) { $g.DrawImage($script:logo, 14, $ly, $logo, $logo) }
    }
    Draw-SideButtons $g $w $h
})

$poll = New-Object Windows.Forms.Timer
$poll.Interval = 180000
$poll.Add_Tick({ Update-Stats })
$poll.Start()
$partnerPoll = New-Object Windows.Forms.Timer
$partnerPoll.Interval = 600000
$partnerPoll.Add_Tick({ Update-Partner })
$partnerPoll.Start()
$slideTimer = New-Object Windows.Forms.Timer
$slideTimer.Interval = 30
$slideTimer.Add_Tick({
    if (-not $script:form -or $script:collapsed) { return }
    if (-not $script:sliding) {
        if (((Get-Date) - $script:slideHold).TotalSeconds -ge 14) { $script:sliding = $true }
        return
    }
    $script:slideY += ($script:form.ClientSize.Height / 18.0)
    if ($script:slideY -ge $script:form.ClientSize.Height) {
        $script:slideY = 0
        $script:sliding = $false
        $script:slide = 1 - [int]$script:slide
        $script:slideHold = Get-Date
    }
    $script:form.Invalidate()
})
$slideTimer.Start()
Update-BarChrome
Apply-BarLayout
$form.Add_Shown({ Update-Stats; Update-Partner })
[void]$form.ShowDialog()
$poll.Stop()
$partnerPoll.Stop()
$slideTimer.Stop()
if ($script:logo) { $script:logo.Dispose() }
$script:cardBrush.Dispose(); $script:cardFill.Dispose(); $script:cardPen.Dispose()
$script:linePen.Dispose(); $script:mutedBrush.Dispose(); $script:fgBrush.Dispose(); $script:okBrush.Dispose(); $script:badBrush.Dispose()
$script:labelFont.Dispose(); $script:valueFont.Dispose(); $script:closeBrush.Dispose(); $script:closeFont.Dispose()
$script:partnerBrush.Dispose(); $script:partnerPen.Dispose()
if ($script:partnerLogo) { $script:partnerLogo.Dispose() }
