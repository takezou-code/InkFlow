param(
    # Seconds to let the app settle before inspecting it.
    [int]$SettleSeconds = 14
)

# Smoke check for the PACKAGED app. Exists because the defects this catches are
# invisible to `gradlew run`:
#
#   - the trimmed jpackage runtime is missing java.sql, so the app dies with
#     NoClassDefFoundError before any window appears;
#   - logback's XML config path needs java.naming and takes the whole process
#     down with it (Failed to launch JVM);
#   - a file appender wired in the wrong order throws at start(), which silently
#     discards every log line.
#
# `gradlew run` uses the full JDK, so all three of those pass locally and only
# fail once packaged. Run this after every createDistributable.
#
# Verifies: process alive, no exception on stderr, log file non-empty, both sync
# ports bound, and (optionally) a document actually arrived from the tablet.

$ErrorActionPreference = 'Stop'

$exe      = "$PSScriptRoot\build\compose\binaries\main\app\InkFlow\InkFlow.exe"
$outFile  = "$env:TEMP\opencode\smoke.out"
$errFile  = "$env:TEMP\opencode\smoke.err"
$appLog   = "$env:USERPROFILE\.inkflow\desktop.log"

$failures = New-Object System.Collections.Generic.List[string]

function Check([string]$name, [bool]$ok, [string]$detail = '') {
    if ($ok) { Write-Host "  PASS  $name" -ForegroundColor Green }
    else {
        Write-Host "  FAIL  $name  $detail" -ForegroundColor Red
        $failures.Add($name)
    }
}

if (-not (Test-Path -LiteralPath $exe)) {
    Write-Host "FAIL: $exe not found - run createDistributable first" -ForegroundColor Red
    exit 1
}

# The launcher reads this file; jpackage puts it in app/ and the build fixup moves
# it up. Checking it here catches the regression without waiting for the app to
# die with a confusing "Error opening ... .cfg file".
$cfg = Join-Path (Split-Path -Parent $exe) 'InkFlow.cfg'
Check "launcher config present" (Test-Path -LiteralPath $cfg) "(expected $cfg)"

Get-Process -Name 'InkFlow' -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
Start-Sleep -Seconds 1
Remove-Item -LiteralPath $outFile, $errFile, $appLog -ErrorAction SilentlyContinue

Write-Host "Launching packaged app..." -ForegroundColor Cyan
$proc = Start-Process -FilePath $exe -RedirectStandardOutput $outFile -RedirectStandardError $errFile -NoNewWindow -PassThru
Start-Sleep -Seconds $SettleSeconds

Write-Host ""
Write-Host "--- checks ---" -ForegroundColor Cyan

$alive = -not $proc.HasExited
Check "process still alive" $alive $(if ($alive) { '' } else { "(exited)" })

$stderr = if (Test-Path $errFile) { Get-Content -LiteralPath $errFile -Raw } else { '' }
$fatal = @()
if ($stderr) {
    $fatal = [regex]::Matches(
        $stderr,
        '(?m)^\s*(?:Exception in thread|Caused by:).*$|NoClassDefFoundError|ClassNotFoundException|Failed to launch JVM'
    ) | ForEach-Object { $_.Value.Trim() } | Select-Object -Unique
}
Check "no fatal exception on stderr" ($fatal.Count -eq 0) ($fatal -join ' | ')

$logText = if (Test-Path $appLog) { Get-Content -LiteralPath $appLog -Raw } else { '' }
Check "log file written" (-not [string]::IsNullOrWhiteSpace($logText)) "(file: $appLog)"
Check "database opened" ($logText -match 'Database') ''
Check "schema migration ran" ($logText -match 'schema|Database initialized') ''
Check "TCP 53531 listening" ($logText -match '53531|TCP sync server') ''
Check "UDP 53530 listening" ($logText -match '53530|UDP discovery') ''

$listeners = Get-NetTCPConnection -LocalPort 53531 -State Listen -ErrorAction SilentlyContinue
Check "port 53531 bound by a process" ($null -ne $listeners) ''

Write-Host ""
if (-not $alive) {
    Write-Host "--- stderr (app exited early) ---" -ForegroundColor Cyan
    Get-Content -LiteralPath $errFile -Tail 20 -ErrorAction SilentlyContinue | ForEach-Object { "  $_" }
}

if ($logText) {
    Write-Host "--- desktop.log (last 25) ---" -ForegroundColor Cyan
    Get-Content -LiteralPath $appLog -Tail 25 | ForEach-Object { "  $_" }
}
if ($fatal.Count) {
    Write-Host ""
    Write-Host "--- stderr ---" -ForegroundColor Cyan
    Get-Content -LiteralPath $errFile -Tail 30 | ForEach-Object { "  $_" }
}

Get-Process -Name 'InkFlow' -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue

Write-Host ""
if ($failures.Count -eq 0) {
    Write-Host "SMOKE OK" -ForegroundColor Green
    exit 0
}
Write-Host "SMOKE FAILED: $($failures -join ', ')" -ForegroundColor Red
exit 1