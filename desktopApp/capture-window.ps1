# Captures the running InkFlow window to a PNG.
#
# Why this exists: `smoke-test.ps1` proves the packaged app starts, and the unit
# tests prove the logic; neither can tell you whether a screen is presentable.
# UI work needs eyes on the actual window, and on a 125%-scaled desktop a
# hand-guessed layout is wrong in ways only a screenshot shows.
#
# Two traps it encodes, both hit while building the desktop UI:
#  - jpackage's launcher re-execs, so the window belongs to a *child* process and
#    `Start-Process -PassThru` + MainWindowHandle reports 0 forever. The window is
#    found by class (SunAwtFrame) and title instead.
#  - another app can own the screen (a fullscreen game covers the desktop), so
#    CopyFromScreen may capture the wrong window. PrintWindow asks this window to
#    paint itself; the screen capture is only a fallback when that comes back blank.
#
# Usage:
#   powershell -NoProfile -ExecutionPolicy Bypass -File desktopApp\capture-window.ps1 `
#       -Dest "$env:TEMP\inkflow.png" -AppArgs "--ui=settings"
#
# `--ui=settings|reader|ai` opens straight onto a screen (see `InkFlowApp`), so a
# screenshot never needs a synthetic click — clicking whatever happens to be in
# front is how you end up typing into someone else's window.
param(
    [string]$Exe = "$PSScriptRoot\build\compose\binaries\main\app\InkFlow\InkFlow.exe",
    [string]$Dest = "$env:TEMP\inkflow-window.png",
    [string]$Title = "InkFlow",
    [string[]]$AppArgs = @(),
    [int]$WaitSeconds = 30,
    [int]$Settle = 3,
    [switch]$KeepRunning
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.Drawing

if (-not ('InkShot' -as [type])) {
    Add-Type @"
using System;
using System.Text;
using System.Runtime.InteropServices;
public class InkShot {
    public delegate bool EnumProc(IntPtr hWnd, IntPtr lParam);
    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }
    [DllImport("user32.dll")] public static extern bool EnumWindows(EnumProc cb, IntPtr lParam);
    [DllImport("user32.dll")] public static extern bool IsWindowVisible(IntPtr hWnd);
    [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetWindowText(IntPtr hWnd, StringBuilder s, int n);
    [DllImport("user32.dll", CharSet=CharSet.Unicode)] public static extern int GetClassName(IntPtr hWnd, StringBuilder s, int n);
    [DllImport("user32.dll")] public static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint pid);
    [DllImport("user32.dll")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT r);
    [DllImport("dwmapi.dll")] public static extern int DwmGetWindowAttribute(IntPtr hWnd, int attr, out RECT r, int size);
    [DllImport("user32.dll")] public static extern bool ShowWindow(IntPtr hWnd, int n);
    [DllImport("user32.dll")] public static extern bool PrintWindow(IntPtr hWnd, IntPtr hdc, uint flags);
}
"@
}

function Find-AppWindow([string]$title) {
    $script:found = [IntPtr]::Zero
    $script:owner = [uint32]0
    [InkShot]::EnumWindows({ param($h, $l)
        if (-not [InkShot]::IsWindowVisible($h)) { return $true }
        $cls = New-Object System.Text.StringBuilder 256
        [InkShot]::GetClassName($h, $cls, 256) | Out-Null
        if ($cls.ToString() -ne 'SunAwtFrame') { return $true }
        $txt = New-Object System.Text.StringBuilder 256
        [InkShot]::GetWindowText($h, $txt, 256) | Out-Null
        if ($txt.ToString() -ne $title) { return $true }
        $script:found = $h
        $p = [uint32]0
        [InkShot]::GetWindowThreadProcessId($h, [ref]$p) | Out-Null
        $script:owner = $p
        return $false
    }, [IntPtr]::Zero) | Out-Null
    return @($script:found, $script:owner)
}

$launcher = $null
$win = Find-AppWindow $Title
if ($win[0] -eq [IntPtr]::Zero) {
    if (-not (Test-Path -LiteralPath $Exe)) {
        Write-Host "FAIL: $Exe not found - run: ink build :desktopApp:createDistributable -Cap 300"
        exit 1
    }
    $launcher = Start-Process -FilePath $Exe -ArgumentList $AppArgs -PassThru
    Write-Host "started launcher pid=$($launcher.Id)"
    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    while ((Get-Date) -lt $deadline) {
        Start-Sleep -Milliseconds 500
        $win = Find-AppWindow $Title
        if ($win[0] -ne [IntPtr]::Zero) { break }
    }
}

$hwnd = $win[0]
$ownerPid = [int]$win[1]
if ($hwnd -eq [IntPtr]::Zero) {
    Write-Host "FAIL: no '$Title' window after ${WaitSeconds}s"
    if ($launcher -and -not $KeepRunning) { Stop-Process -Id $launcher.Id -Force -ErrorAction SilentlyContinue }
    exit 1
}
Write-Host "window hwnd=$hwnd pid=$ownerPid"

# Let the first frame paint (thumbnails and the PDF raster are async).
Start-Sleep -Seconds $Settle
[InkShot]::ShowWindow($hwnd, 9) | Out-Null   # SW_RESTORE
Start-Sleep -Milliseconds 500

$rect = New-Object InkShot+RECT
$ok = [InkShot]::DwmGetWindowAttribute($hwnd, 9, [ref]$rect, 16) -eq 0
if (-not $ok) { [InkShot]::GetWindowRect($hwnd, [ref]$rect) | Out-Null }
$w = $rect.Right - $rect.Left
$h = $rect.Bottom - $rect.Top
if ($w -le 0 -or $h -le 0) {
    Write-Host "FAIL: window rect empty ($w x $h)"
    if ($launcher -and -not $KeepRunning) { Stop-Process -Id $launcher.Id -Force -ErrorAction SilentlyContinue }
    exit 1
}

New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Dest) | Out-Null
$bmp = New-Object System.Drawing.Bitmap($w, $h)

$g = [System.Drawing.Graphics]::FromImage($bmp)
$printed = $false
try {
    $hdc = $g.GetHdc()
    $printed = [InkShot]::PrintWindow($hwnd, $hdc, 2)   # PW_RENDERFULLCONTENT
    $g.ReleaseHdc($hdc)
} finally {
    $g.Dispose()
}

# A window that refused to paint comes back uniform; fall back to the screen.
function Test-Blank([System.Drawing.Bitmap]$b) {
    $first = $null
    $varied = 0
    for ($x = 8; $x -lt $b.Width; $x += [Math]::Max(1, [int]($b.Width / 24))) {
        for ($y = 8; $y -lt $b.Height; $y += [Math]::Max(1, [int]($b.Height / 16))) {
            $c = $b.GetPixel($x, $y).ToArgb()
            if ($null -eq $first) { $first = $c } elseif ($c -ne $first) { $varied++ }
        }
    }
    return ($varied -lt 3)
}

if (-not $printed -or (Test-Blank $bmp)) {
    Write-Host "PrintWindow gave nothing usable; capturing the screen"
    $g2 = [System.Drawing.Graphics]::FromImage($bmp)
    try { $g2.CopyFromScreen($rect.Left, $rect.Top, 0, 0, (New-Object System.Drawing.Size($w, $h))) }
    finally { $g2.Dispose() }
}

$bmp.Save($Dest, [System.Drawing.Imaging.ImageFormat]::Png)
$bmp.Dispose()

# Verify the PNG magic bytes rather than trusting the file's presence: a
# truncated capture looks exactly like a success until you open it.
$bytes = [System.IO.File]::ReadAllBytes($Dest)
$magic = $bytes.Length -ge 8 -and $bytes[0] -eq 0x89 -and $bytes[1] -eq 0x50 -and $bytes[2] -eq 0x4E -and $bytes[3] -eq 0x47
if (-not $magic) {
    Write-Host "FAIL: not a PNG ($($bytes.Length) bytes)"
    exit 1
}

Write-Host "OK $Dest ($w x $h, $($bytes.Length) bytes)"

if (-not $KeepRunning) {
    if ($launcher) { Stop-Process -Id $launcher.Id -Force -ErrorAction SilentlyContinue }
    if ($ownerPid -gt 0) { Stop-Process -Id $ownerPid -Force -ErrorAction SilentlyContinue }
}
exit 0
