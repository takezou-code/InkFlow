@echo off
rem ============================================================================
rem  Double-click this file to launch the InkFlow desktop app.
rem
rem  If the sources are newer than the built EXE, it recompiles first
rem  (about a minute or two). Use the flags below to change that.
rem
rem    run-desktop.cmd --no-build   always launch the existing EXE (fastest)
rem    run-desktop.cmd --build      force a rebuild before launching
rem    run-desktop.cmd --rebuild    clean first, then a full rebuild
rem
rem  This file is a shim only -- all the logic lives in tools\launch-desktop.ps1.
rem  Two reasons for the split: the logic becomes testable on its own, and the
rem  command-line flags no longer have to survive cmd.exe quote parsing.
rem
rem  NOTE: deliberately ASCII-only. A .cmd must NOT carry a UTF-8 BOM (cmd tries
rem  to execute the BOM bytes as commands) and cmd's handling of non-ASCII in a
rem  BOM-less file depends on the active code page, which is not worth betting a
rem  launcher on. Chinese comments would gain nothing here -- this is a shim.
rem ============================================================================

setlocal

set "SCRIPT=%~dp0tools\launch-desktop.ps1"

if not exist "%SCRIPT%" (
    echo [ERROR] Cannot find "%SCRIPT%".
    echo Make sure run-desktop.cmd still sits in the repository root.
    pause
    exit /b 1
)

rem PowerShell parameter binding rejects "--no-build" outright, so the GNU-style
rem spelling has to be translated here rather than inside the script.
set "PSARGS="
:parse
if "%~1"=="" goto parsed
if /i "%~1"=="--no-build" set "PSARGS=%PSARGS% -NoBuild" & shift & goto parse
if /i "%~1"=="--build"    set "PSARGS=%PSARGS% -Build"    & shift & goto parse
if /i "%~1"=="--rebuild"  set "PSARGS=%PSARGS% -Rebuild"  & shift & goto parse
if /i "%~1"=="-NoBuild"   set "PSARGS=%PSARGS% -NoBuild"  & shift & goto parse
if /i "%~1"=="-Build"     set "PSARGS=%PSARGS% -Build"    & shift & goto parse
if /i "%~1"=="-Rebuild"   set "PSARGS=%PSARGS% -Rebuild"  & shift & goto parse
echo Unknown argument: %~1
shift
goto parse
:parsed

powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT%"%PSARGS%
if errorlevel 1 pause
