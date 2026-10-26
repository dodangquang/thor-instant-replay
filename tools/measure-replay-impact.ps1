#Requires -Version 5.1
<#
.SYNOPSIS
    Measures the frame-pacing impact of Android Replay on an AYN Thor while a game runs.

.DESCRIPTION
    Runs the three comparison states from the optimisation brief and reports the
    Thor Native FG `gap_max_ms` distribution plus the replay app's CPU/RAM:

        off    - replay stopped (the "no recorder" baseline)
        30s    - replay buffering a 30 second window
        120s   - replay buffering a 120 second window

    Each state is measured for -Seconds seconds, and the p95 / mean / max of the
    per-second `gap_max_ms` windows are reported together with app CPU and RSS.

    Prerequisites on the device:
        adb shell pm grant com.thor.instantreplay android.permission.RECORD_AUDIO
        adb shell pm grant com.thor.instantreplay android.permission.POST_NOTIFICATIONS
        adb shell appops set com.thor.instantreplay PROJECT_MEDIA allow
        adb shell dumpsys deviceidle whitelist +com.thor.instantreplay

    The same game scene must be on screen for every state. Start the game through
    DroidDeck first, then run this script.

.EXAMPLE
    .\tools\measure-replay-impact.ps1 -All
    .\tools\measure-replay-impact.ps1 -State off -Seconds 60
    .\tools\measure-replay-impact.ps1 -State 120s -Seconds 60 -OutFile results\after-120s.json
#>
param(
    [string]$Serial = 'c2b670a0',
    [ValidateSet('off', '30s', '60s', '120s')]
    [string]$State = 'off',
    [switch]$All,
    [int]$Seconds = 60,
    [int]$WarmupSeconds = 8,
    [string]$OutFile = '',
    [string]$Package = 'com.thor.instantreplay'
)

$ErrorActionPreference = 'Stop'
$states = if ($All) { @('off', '30s', '120s') } else { @($State) }

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$AdbArgs)
    $out = & adb -s $Serial @AdbArgs 2>&1
    return $out
}

function Assert-Device {
    $devices = & adb devices 2>&1
    if (-not ($devices -match "^\s*$([regex]::Escape($Serial))\s+device")) {
        throw "Device $Serial is not attached or not authorised. Current devices:`n$($devices -join "`n")"
    }
}

function Set-ReplayState {
    param([string]$Wanted)

    # Stop any running instance first. ACTION_STOP sets the persistent stop latch,
    # so nothing can silently resurrect capture while the baseline is measured.
    Invoke-Adb shell am broadcast -a "$Package.ACTION_STOP" -p $Package | Out-Null
    Start-Sleep -Milliseconds 800

    if ($Wanted -eq 'off') {
        Invoke-Adb shell am force-stop $Package | Out-Null
        Start-Sleep -Milliseconds 500
        Write-Host '  replay: stopped' -ForegroundColor DarkGray
        return
    }

    $duration = $Wanted.TrimEnd('s')
    # TransparentPermissionActivity acquires the MediaProjection token silently
    # (needs the PROJECT_MEDIA app-op) and hands the window length to the service.
    Invoke-Adb shell am start -n "$Package/.TransparentPermissionActivity" --ei extra_duration_seconds $duration | Out-Null
    Start-Sleep -Seconds 3

    $svc = (Invoke-Adb shell dumpsys activity services $Package) -join "`n"
    if ($svc -notmatch 'ReplayService') {
        throw "Replay service did not start for state '$Wanted'. Check the PROJECT_MEDIA app-op and that capture permission is granted."
    }
    Write-Host "  replay: buffering ${duration}s" -ForegroundColor DarkGray
}

function Get-GapSamples {
    $raw = (Invoke-Adb logcat -d -s ThorNativeLayer:I) -join "`n"
    $lines = $raw -split "`n" | Where-Object { $_ -match 'THOR_METRICS' }
    $samples = @()
    foreach ($line in $lines) {
        $gap = [regex]::Match($line, 'gap_max_ms\s*[=:]\s*([0-9.]+)')
        if (-not $gap.Success) { $gap = [regex]::Match($line, 'gap_max\s*[=:]\s*([0-9.]+)') }
        if (-not $gap.Success) { continue }
        $disp = [regex]::Match($line, 'display_fps\s*[=:]\s*([0-9.]+)')
        if (-not $disp.Success) { $disp = [regex]::Match($line, 'display_fps\s*[=:]\s*([0-9.]+)') }
        $src = [regex]::Match($line, 'source_fps\s*[=:]\s*([0-9.]+)')
        $samples += [pscustomobject]@{
            GapMaxMs   = [double]$gap.Groups[1].Value
            DisplayFps = if ($disp.Success) { [double]$disp.Groups[1].Value } else { $null }
            SourceFps  = if ($src.Success) { [double]$src.Groups[1].Value } else { $null }
        }
    }
    return $samples
}

function Get-Percentile {
    param([double[]]$Values, [double]$Percentile)
    if (-not $Values -or $Values.Count -eq 0) { return $null }
    $sorted = @($Values | Sort-Object)
    $rank = [math]::Ceiling(($Percentile / 100.0) * $sorted.Count) - 1
    if ($rank -lt 0) { $rank = 0 }
    return [math]::Round($sorted[$rank], 1)
}

function Get-Aggregate {
    param($Values, [string]$Op, [int]$Digits = 1)
    $clean = @($Values | Where-Object { $null -ne $_ })
    if ($clean.Count -eq 0) { return $null }
    $value = if ($Op -eq 'max') {
        ($clean | Measure-Object -Maximum).Maximum
    } else {
        ($clean | Measure-Object -Average).Average
    }
    return [math]::Round($value, $Digits)
}

function Get-AppUsage {
    # top -o sorts output columns; the header line tells us which is which.
    $out = (Invoke-Adb shell top -b -n 1 -o %CPU,RES,ARGS) -join "`n"
    $cpu = @()
    $resKb = @()
    foreach ($line in ($out -split "`n")) {
        if ($line -notmatch [regex]::Escape($Package)) { continue }
        if ($line -match '^\s*([0-9.]+)\s+([0-9]+)([KMG]?)\s+(.*)$') {
            $cpu += [double]$matches[1]
            $value = [double]$matches[2]
            switch ($matches[3]) {
                'M' { $value *= 1024 }
                'G' { $value *= 1024 * 1024 }
            }
            $resKb += $value
        }
    }
    return [pscustomobject]@{
        CpuMean = if ($cpu.Count) { [math]::Round((($cpu | Measure-Object -Average).Average), 2) } else { $null }
        CpuMax  = if ($cpu.Count) { [math]::Round((($cpu | Measure-Object -Maximum).Maximum), 2) } else { $null }
        ResMeanMb = if ($resKb.Count) { [math]::Round((($resKb | Measure-Object -Average).Average / 1024), 1) } else { $null }
        ResMaxMb  = if ($resKb.Count) { [math]::Round((($resKb | Measure-Object -Maximum).Maximum / 1024), 1) } else { $null }
    }
}

function Measure-State {
    param([string]$Wanted)

    Write-Host ""
    Write-Host "=== state: $Wanted ===" -ForegroundColor Cyan
    Set-ReplayState -Wanted $Wanted

    Write-Host "  warm-up ${WarmupSeconds}s ..." -ForegroundColor DarkGray
    Start-Sleep -Seconds $WarmupSeconds

    Invoke-Adb logcat -c | Out-Null

    $cpuSamples = @()
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        $cpuSamples += Get-AppUsage
        Start-Sleep -Seconds 5
    }

    $gaps = Get-GapSamples
    $gapValues = @($gaps | ForEach-Object { $_.GapMaxMs })
    $displayFps = @($gaps | Where-Object { $null -ne $_.DisplayFps } | ForEach-Object { $_.DisplayFps })

    $cpuMean = Get-Aggregate -Values ($cpuSamples | ForEach-Object { $_.CpuMean }) -Op 'mean' -Digits 2
    $cpuMax = Get-Aggregate -Values ($cpuSamples | ForEach-Object { $_.CpuMax }) -Op 'max' -Digits 2
    $resMax = Get-Aggregate -Values ($cpuSamples | ForEach-Object { $_.ResMaxMb }) -Op 'max' -Digits 1

    $result = [pscustomobject]@{
        State        = $Wanted
        Seconds      = $Seconds
        Windows      = $gapValues.Count
        GapMeanMs    = Get-Aggregate -Values $gapValues -Op 'mean'
        GapP50Ms     = Get-Percentile -Values $gapValues -Percentile 50
        GapP95Ms     = Get-Percentile -Values $gapValues -Percentile 95
        GapMaxMs     = Get-Aggregate -Values $gapValues -Op 'max'
        Windows33Ms  = @($gapValues | Where-Object { $_ -le 33.4 }).Count
        DisplayFpsMean = Get-Aggregate -Values $displayFps -Op 'mean'
        AppCpuMeanPct  = $cpuMean
        AppCpuMaxPct   = $cpuMax
        AppResMaxMb    = $resMax
    }

    Write-Host ("  windows={0}  gap mean={1}  p50={2}  p95={3}  max={4} ms" -f `
        $result.Windows, $result.GapMeanMs, $result.GapP50Ms, $result.GapP95Ms, $result.GapMaxMs) -ForegroundColor White
    Write-Host ("  app cpu mean={0}%  peak={1}%   res peak={2} MB   display_fps~{3}" -f `
        $result.AppCpuMeanPct, $result.AppCpuMaxPct, $result.AppResMaxMb, $result.DisplayFpsMean) -ForegroundColor White

    return $result
}

Assert-Device
Write-Host "Measuring on $Serial for ${Seconds}s per state" -ForegroundColor Yellow

$results = @()
foreach ($s in $states) {
    $results += Measure-State -Wanted $s
}

Write-Host ""
Write-Host "=== summary ===" -ForegroundColor Cyan
$results | Format-Table State, Windows, GapMeanMs, GapP50Ms, GapP95Ms, GapMaxMs, AppCpuMeanPct, AppResMaxMb -AutoSize

Write-Host "Target: gap p95 <= 33 ms, app CPU < 3%." -ForegroundColor Yellow
$fail = $results | Where-Object { $_.State -ne 'off' -and $null -ne $_.GapP95Ms -and $_.GapP95Ms -gt 33.4 }
if ($fail) {
    Write-Host "MISS: $($fail.State -join ', ') still exceed the 33 ms p95 budget." -ForegroundColor Red
} else {
    Write-Host "PASS: every buffering state is within the 33 ms p95 budget." -ForegroundColor Green
}

if ($OutFile) {
    $dir = Split-Path -Parent $OutFile
    if ($dir -and -not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    $results | ConvertTo-Json -Depth 4 | Set-Content -Path $OutFile -Encoding utf8
    Write-Host "Wrote $OutFile" -ForegroundColor DarkGray
}
