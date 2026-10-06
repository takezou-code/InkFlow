#requires -Version 5.1
<#
.SYNOPSIS
    啟動 InkFlow 桌面版；必要時先編譯。

.DESCRIPTION
    由 run-desktop.cmd 呼叫，也可以直接跑：

        powershell -ExecutionPolicy Bypass -File tools\launch-desktop.ps1

    三種模式：
      （預設）      EXE 不存在或落後於原始碼時先編譯，再啟動
      --no-build    永遠直接啟動現有 EXE（最快）
      --rebuild     先 clean 再完整編譯（編譯狀態壞掉時用）

.EXAMPLE
    .\launch-desktop.ps1 -NoBuild
#>
[CmdletBinding()]
param(
    [switch]$Build,
    [switch]$NoBuild,
    [switch]$Rebuild
)

$ErrorActionPreference = 'Stop'

# The repo root is two levels up from tools\. Resolving from $PSScriptRoot rather
# than the working directory means the script works no matter where it is called
# from — double-click, task scheduler, or another script.
$Root = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $Root

$Exe = Join-Path $Root 'desktop-app\build\compose\binaries\main\app\InkFlow\InkFlow.exe'
$Wrapper = Join-Path $Root 'gradlew.bat'

function Write-Step($Message) { Write-Host "==> $Message" -ForegroundColor Cyan }
function Write-Fail($Message) { Write-Host "[錯誤] $Message" -ForegroundColor Red }

# --------------------------------------------------------------------- Java --
# gradlew refuses to run without JAVA_HOME, and this machine ships with it unset
# and no `java` on PATH, so we go find a JDK instead of failing with the
# wrapper's own error message and no hint about what to do about it.
function Resolve-Jdk {
    $candidates = @()

    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }

    $candidates += Join-Path $env:LOCALAPPDATA 'Programs\Android Studio\jbr'
    $candidates += Join-Path $env:ProgramFiles 'Android\Android Studio\jbr'

    foreach ($root in @($env:ProgramFiles, ${env:ProgramFiles(x86)})) {
        if (-not $root -or -not (Test-Path -LiteralPath $root)) { continue }
        foreach ($vendor in @('Eclipse Adoptium', 'Microsoft', 'Zulu', 'Java')) {
            $dir = Join-Path $root $vendor
            if (-not (Test-Path -LiteralPath $dir)) { continue }
            Get-ChildItem -LiteralPath $dir -Directory -ErrorAction SilentlyContinue |
                ForEach-Object { $candidates += (Join-Path $_.FullName 'bin\java.exe') }
        }
    }

    foreach ($c in $candidates) {
        # Accept either the JDK root or a direct java.exe path.
        $exe = if (Test-Path -LiteralPath $c -PathType Leaf) { $c }
               else { Join-Path $c 'bin\java.exe' }
        if (Test-Path -LiteralPath $exe -PathType Leaf) { return Split-Path -Parent (Split-Path -Parent $exe) }
    }
    return $null
}

$jdk = Resolve-Jdk
if (-not $jdk) {
    Write-Fail '找不到 Java，無法編譯。'
    Write-Host ''
    Write-Host '  請設定 JAVA_HOME 指向 JDK 17 或更新版本：'
    Write-Host '    setx JAVA_HOME "C:\path\to\jdk"'
    Write-Host ''
    Read-Host '按 Enter 結束' | Out-Null
    exit 1
}
if (-not $env:JAVA_HOME) { $env:JAVA_HOME = $jdk }

if (-not (Test-Path -LiteralPath $Wrapper)) {
    Write-Fail "找不到 gradlew.bat：$Wrapper"
    Read-Host '按 Enter 結束' | Out-Null
    exit 1
}

# ---------------------------------------------------------------- 過期判定 --
# Compare against the newest source/build-script timestamp. Only the `src` trees
# are scanned, never the build outputs — including build/ would make the outputs
# look perpetually newer than the EXE they produced, and nothing would ever be
# considered fresh.
function Test-Stale {
    if (-not (Test-Path -LiteralPath $Exe)) { return 'MISSING' }

    $roots = @('shared\src', 'desktop-app\src', 'app\src')
    $newest = $null

    foreach ($r in $roots) {
        $dir = Join-Path $Root $r
        if (-not (Test-Path -LiteralPath $dir)) { continue }
        $hit = Get-ChildItem -LiteralPath $dir -Recurse -File -Include *.kt, *.kts -ErrorAction SilentlyContinue |
            Measure-Object -Property LastWriteTime -Maximum
        if ($hit.Maximum -and (-not $newest -or $hit.Maximum -gt $newest)) { $newest = $hit.Maximum }
    }

    $configFiles = @(
        (Join-Path $Root 'gradle\libs.versions.toml'),
        (Join-Path $Root 'settings.gradle.kts'),
        (Join-Path $Root 'desktop-app\build.gradle.kts'),
        (Join-Path $Root 'shared\build.gradle.kts')
    )
    foreach ($c in $configFiles) {
        if (-not (Test-Path -LiteralPath $c)) { continue }
        $t = (Get-Item -LiteralPath $c).LastWriteTime
        if (-not $newest -or $t -gt $newest) { $newest = $t }
    }

    if (-not $newest) { return 'FRESH' }
    if ($newest -gt (Get-Item -LiteralPath $Exe).LastWriteTime) { return 'STALE' }
    return 'FRESH'
}

# -------------------------------------------------------------------- 編譯 --
$state = if ($NoBuild) { 'FRESH' } else { Test-Stale }

if ($Rebuild) { $state = 'STALE' }

$needBuild = $Build -or $Rebuild -or ($state -eq 'STALE') -or ($state -eq 'MISSING')

if (-not $needBuild) {
    Write-Host "[略過] 編譯（$state）。要用 --build 強制重新編譯。" -ForegroundColor DarkGray
}
else {
    Write-Host ''

    if ($Rebuild) {
        Write-Step '清理桌面版編譯產物'
        & $Wrapper ':desktopApp:clean' '--console=plain'
        if ($LASTEXITCODE -ne 0) { Write-Fail 'clean 失敗。'; Read-Host '按 Enter 結束' | Out-Null; exit 1 }
    }

    $why = if ($state -eq 'MISSING') { '尚未建置' }
           elseif ($Rebuild) { '要求完整重建' }
           elseif ($Build) { '要求重新編譯' }
           else { '原始碼比 EXE 新' }

    Write-Step "編譯桌面版（$why）— 這需要一到兩分鐘"
    Write-Host ''

    & $Wrapper ':desktopApp:createDistributable' '--console=plain'
    if ($LASTEXITCODE -ne 0) {
        Write-Host ''
        Write-Fail '編譯失敗，上面的輸出是原因。'
        Read-Host '按 Enter 結束' | Out-Null
        exit 1
    }
}

if (-not (Test-Path -LiteralPath $Exe)) {
    Write-Host ''
    Write-Fail "編譯看似成功，但找不到 InkFlow.exe：$Exe"
    Write-Host '  試試 --rebuild 重新完整建置。'
    Read-Host '按 Enter 結束' | Out-Null
    exit 1
}

Write-Step "啟動 $Exe"
Write-Host ''
Start-Process -FilePath $Exe
exit 0
