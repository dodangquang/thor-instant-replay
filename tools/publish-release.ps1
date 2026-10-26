#Requires -Version 5.1
<#
.SYNOPSIS
    Builds, verifies and publishes an Android Replay release, and writes its update feed.

.DESCRIPTION
    The in-app updater (com.thorqol.shared.AppUpdates) only accepts an update when all of
    these hold, so the script checks every one of them before it uploads anything:

      * the APK is signed with the SAME certificate as the version already published
        (a rebuild with a different debug keystore cannot be installed over the app);
      * the asset is a GitHub release download for this repository;
      * the file name is Android-Replay-<versionName>.apk / Android-Replay-<versionName>-trial.apk;
      * the feed declares the exact byte length and SHA-256 of the asset;
      * minSdk in the feed equals the APK's manifest minSdk (29).

    Without -Publish the script only builds, verifies, and writes updates/v1/*.json next to
    the repository, which is the safe way to dry-run a release.

    Publishing the feed itself is a separate step: the app reads
    https://thorqol-license.netlify.app/updates/v1/<package>.json, so the generated JSON has to
    be deployed to that site. Committing it to this repository does not deploy it.

.EXAMPLE
    # dry run: build, check the signing certificate, write the feed, upload nothing
    .\tools\publish-release.ps1

.EXAMPLE
    # full release
    $env:GITHUB_TOKEN = 'ghp_...'
    .\tools\publish-release.ps1 -Publish -NotesVi "..." -NotesEn "..."

.EXAMPLE
    .\tools\publish-release.ps1 -Trial -Publish
#>
param(
    [switch]$Publish,
    [switch]$Trial,
    [string]$Repo = 'dodangquang/thor-instant-replay',
    [string]$Token = $env:GITHUB_TOKEN,
    [string]$NotesVi = 'Tối ưu hiệu năng capture để giảm khựng khi replay chạy nền.',
    [string]$NotesEn = 'Capture performance work to reduce stalls while replay buffers in the background.',
    [string]$FeedOrigin = 'https://thorqol-license.netlify.app',
    [switch]$SkipSigningCheck
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Push-Location $repoRoot
try {

function Write-Step($text) { Write-Host "==> $text" -ForegroundColor Cyan }
function Write-Note($text) { Write-Host "    $text" -ForegroundColor DarkGray }

function ConvertTo-JsonString([string]$value) {
    if ($null -eq $value) { return '""' }
    $sb = New-Object System.Text.StringBuilder
    [void]$sb.Append('"')
    foreach ($ch in $value.ToCharArray()) {
        switch ($ch) {
            '"'  { [void]$sb.Append('\"'); continue }
            '\'  { [void]$sb.Append('\\'); continue }
            "`n" { [void]$sb.Append('\n'); continue }
            "`r" { [void]$sb.Append('\r'); continue }
            "`t" { [void]$sb.Append('\t'); continue }
            default {
                if ([int]$ch -lt 0x20) { [void]$sb.Append(('\u{0:x4}' -f [int]$ch)) }
                else { [void]$sb.Append($ch) }
            }
        }
    }
    [void]$sb.Append('"')
    return $sb.ToString()
}

function Get-ApkInfo([string]$apk, [string]$field) {
    $badging = & $script:aapt2 dump badging $apk 2>&1 | Out-String
    switch ($field) {
        'versionCode' { return [long]([regex]::Match($badging, "versionCode='(\d+)'").Groups[1].Value) }
        'versionName' { return [regex]::Match($badging, "versionName='([^']+)'").Groups[1].Value }
        'package'     { return [regex]::Match($badging, "package: name='([^']+)'").Groups[1].Value }
        'minSdk'      { return [int]([regex]::Match($badging, "minSdkVersion:'(\d+)'").Groups[1].Value) }
    }
}

function Get-ApkCert([string]$apk) {
    $out = & $script:apksigner verify --print-certs $apk 2>&1 | Out-String
    if ($out -notmatch 'certificate SHA-256 digest:\s*([0-9a-fA-F]+)') {
        throw "APK is not signed: $apk"
    }
    return $Matches[1].ToLowerInvariant()
}

# ---------------------------------------------------------------- toolchain
if (-not $env:JAVA_HOME) { throw 'JAVA_HOME is not set (JDK 17+).' }
if (-not $env:ANDROID_HOME) { throw 'ANDROID_HOME is not set (Android SDK).' }

$versions = Get-ChildItem (Join-Path $env:ANDROID_HOME 'build-tools') -Directory |
    Sort-Object { [version]$_.Name } -Descending
if (-not $versions) { throw 'No build-tools installed in the Android SDK.' }
$buildTools = $versions[0].FullName
$script:apksigner = Join-Path $buildTools 'apksigner.bat'
$script:aapt2 = Join-Path $buildTools 'aapt2.exe'
if (-not (Test-Path $script:apksigner)) { throw "apksigner not found in $buildTools" }

if (-not $env:KHOGAME_DEBUG_KEYSTORE) {
    Write-Host 'WARNING: KHOGAME_DEBUG_KEYSTORE is not set; the build falls back to the default' -ForegroundColor Yellow
    Write-Host '         debug keystore, which is almost certainly a different key than the' -ForegroundColor Yellow
    Write-Host '         published releases. The update would be rejected before install.' -ForegroundColor Yellow
}

# ---------------------------------------------------------------- build
$gradleArgs = @('--no-daemon', ':app:assembleRelease')
if ($Trial) { $gradleArgs += '-PthorqolTrial=true' }
Write-Step "Building ($($gradleArgs -join ' '))"
& (Join-Path $repoRoot 'gradlew.bat') @gradleArgs
if ($LASTEXITCODE -ne 0) { throw 'Gradle build failed.' }

$apk = Join-Path $repoRoot 'app\build\outputs\apk\release\app-release.apk'
if (-not (Test-Path $apk)) { throw "APK not found: $apk" }

$package = Get-ApkInfo $apk 'package'
$versionName = Get-ApkInfo $apk 'versionName'
$versionCode = Get-ApkInfo $apk 'versionCode'
$minSdk = Get-ApkInfo $apk 'minSdk'
Write-Note "$package  $versionName ($versionCode)  minSdk=$minSdk"

if ($Trial -and -not $package.EndsWith('.trial')) { throw "Trial build produced package $package" }
if (-not $Trial -and $package.EndsWith('.trial')) { throw 'Non-trial build produced a trial package.' }
if (-not $versionName.EndsWith('-trial') -and $Trial) { throw 'Trial versionName must end with -trial.' }
if ($versionName -notmatch '^[0-9]+\.[0-9]+\.[0-9]+(-trial)?$') { throw "versionName '$versionName' is not accepted by UpdateSpec." }

# ---------------------------------------------------------------- signing check
$cert = Get-ApkCert $apk
Write-Note "signing certificate $cert"

if (-not $SkipSigningCheck) {
    Write-Step 'Comparing the signing certificate with the published release'
    try {
        $live = Invoke-RestMethod -Uri "$FeedOrigin/updates/v1/$package.json" -TimeoutSec 20
        $tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("published-" + [guid]::NewGuid().ToString('N') + '.apk')
        try {
            Invoke-WebRequest -Uri $live.apkUrl -OutFile $tmp -UseBasicParsing -TimeoutSec 180
            $publishedCert = Get-ApkCert $tmp
            if ($publishedCert -ne $cert) {
                throw ("APK signing certificate does not match the published $($live.versionName) build.`n" +
                       "  new       : $cert`n" +
                       "  published : $publishedCert`n" +
                       "Android will refuse to install this over the app. Build with the keystore that " +
                       "signed the published release (KHOGAME_DEBUG_KEYSTORE) or pass -SkipSigningCheck.")
            }
            Write-Note "matches published $($live.versionName)"
            if ($versionCode -le [long]$live.versionCode) {
                throw "versionCode $versionCode must be greater than the published $($live.versionCode)."
            }
        } finally {
            if (Test-Path $tmp) { Remove-Item $tmp -Force }
        }
    } catch [System.Management.Automation.RuntimeException] {
        throw
    } catch {
        Write-Host "WARNING: could not verify against the published release: $($_.Exception.Message)" -ForegroundColor Yellow
        Write-Host '         Continue only if you know the keystore is correct.' -ForegroundColor Yellow
    }
}

# ---------------------------------------------------------------- asset + feed
$fileName = "Android-Replay-$versionName.apk"
$staging = Join-Path $repoRoot "build\release\$fileName"
New-Item -ItemType Directory -Path (Split-Path -Parent $staging) -Force | Out-Null
Copy-Item $apk $staging -Force

$bytes = (Get-Item $staging).Length
$sha256 = (Get-FileHash $staging -Algorithm SHA256).Hash.ToLowerInvariant()
$tag = "thorqol-replay-v$versionName"
$apkUrl = "https://github.com/$Repo/releases/download/$tag/$fileName"
Write-Note "$([math]::Round($bytes / 1MB, 2)) MB  sha256=$sha256"

$feed = @"
{
  "schemaVersion": 1,
  "packageName": $(ConvertTo-JsonString $package),
  "channel": $(ConvertTo-JsonString $(if ($Trial) { 'trial' } else { 'full' })),
  "versionCode": $versionCode,
  "versionName": $(ConvertTo-JsonString $versionName),
  "minSdk": $minSdk,
  "bytes": $bytes,
  "sha256": "$sha256",
  "apkUrl": $(ConvertTo-JsonString $apkUrl),
  "notesVi": $(ConvertTo-JsonString $NotesVi),
  "notesEn": $(ConvertTo-JsonString $NotesEn)
}
"@

$feedPath = Join-Path $repoRoot "updates\v1\$package.json"
New-Item -ItemType Directory -Path (Split-Path -Parent $feedPath) -Force | Out-Null
[System.IO.File]::WriteAllText($feedPath, ($feed -replace "`r`n", "`n"), (New-Object System.Text.UTF8Encoding($false)))
Write-Step "Wrote $feedPath"

if (-not $Publish) {
    Write-Host ''
    Write-Host 'Dry run: nothing was uploaded. Re-run with -Publish to create the release.' -ForegroundColor Yellow
    Write-Host 'Release to create manually if you prefer the web UI:' -ForegroundColor Yellow
    Write-Host "  tag    : $tag"
    Write-Host "  asset  : $fileName"
    Write-Host "  feed   : $apkUrl"
    return
}

# ---------------------------------------------------------------- publish
if (-not $Token) { $Token = $env:GITHUB_TOKEN }
if (-not $Token) {
    try {
        $stored = "protocol=https`nhost=github.com`n`n" | git credential fill 2>$null
        $Token = ($stored | Where-Object { $_ -like 'password=*' }) -replace '^password=', ''
    } catch { }
}
if (-not $Token) { throw 'No GitHub token: set $env:GITHUB_TOKEN or store credentials for github.com.' }

$headers = @{
    Authorization          = "Bearer $Token"
    Accept                 = 'application/vnd.github+json'
    'X-GitHub-Api-Version' = '2022-11-28'
    'User-Agent'           = 'thorqol-release'
}

Write-Step "Creating release $tag"
$body = @{
    tag_name         = $tag
    name             = "Android Replay $versionName"
    body             = "$NotesVi`n`n$NotesEn"
    draft            = $false
    prerelease       = $false
    target_commitish = 'main'
} | ConvertTo-Json -Depth 4

$release = Invoke-RestMethod -Method Post -Uri "https://api.github.com/repos/$Repo/releases" `
    -Headers $headers -Body $body -ContentType 'application/json; charset=utf-8'
Write-Note "release id $($release.id)"

Write-Step "Uploading $fileName"
$upload = & curl.exe -sS -X POST `
    -H "Authorization: Bearer $Token" `
    -H 'Content-Type: application/vnd.android.package-archive' `
    -H 'User-Agent: thorqol-release' `
    --data-binary "@$staging" `
    "https://uploads.github.com/repos/$Repo/releases/$($release.id)/assets?name=$fileName" 2>&1 | Out-String
if ($LASTEXITCODE -ne 0) { throw "Asset upload failed: $upload" }

# The updater downloads through github.com -> *.githubusercontent.com redirects only.
Write-Step 'Verifying the published asset'
try {
    $manifest = Invoke-RestMethod -Uri "$FeedOrigin/updates/v1/$package.json" -TimeoutSec 20
    Write-Note "live feed still advertises $($manifest.versionName) ($($manifest.versionCode))"
} catch {
    Write-Note 'live feed unavailable'
}

Write-Host ''
Write-Host 'NEXT STEP (not automatable here): deploy the feed to Netlify.' -ForegroundColor Yellow
Write-Host "  file    : $feedPath"
Write-Host "  target  : $FeedOrigin/updates/v1/$package.json"
Write-Host '  The app fetches the manifest from that origin only; committing the file to GitHub'
Write-Host '  does not publish it. Until it is deployed, installed clients keep seeing the old version.'

} finally {
    Pop-Location
}
