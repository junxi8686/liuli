@echo off
REM Liuli frame-timing measurement.
REM
REM Resets gfxinfo, drives a scripted scroll over the chat list, then prints the
REM jank counters. Run it before and after a change: "Janky frames" and the 90th
REM percentile are the numbers that matter.
REM
REM   tools\perf.cmd            measure the release package
REM   tools\perf.cmd .debug     measure the debug package instead
setlocal
set "ROOT=C:\Users\junxi\Desktop\Android-Toolchain"
set "ADB=%ROOT%\01-android-sdk\platform-tools\adb.exe"
set "PKG=com.liuli.btchat%~1"

echo === reset ===
"%ADB%" shell dumpsys gfxinfo %PKG% reset >nul 2>&1
"%ADB%" shell am force-stop %PKG%
"%ADB%" shell am start -n %PKG%/.MainActivity >nul
timeout /t 8 /nobreak >nul

echo === drive scroll ===
for /l %%i in (1,1,10) do (
  "%ADB%" shell input swipe 720 2200 720 800 160 >nul
  "%ADB%" shell input swipe 720 800 720 2200 160 >nul
)
timeout /t 3 /nobreak >nul

echo === stats ===
"%ADB%" shell dumpsys gfxinfo %PKG% > "%TEMP%\liuli-gfxinfo.txt" 2>&1
findstr /C:"Total frames rendered" /C:"Janky frames" /C:"Number Missed Vsync" /C:"Number Slow UI thread" /C:"Number Slow bitmap uploads" /C:"Number Slow issue draw commands" /C:"50th percentile" /C:"90th percentile" /C:"95th percentile" /C:"99th percentile" "%TEMP%\liuli-gfxinfo.txt"
echo.
echo full report: %TEMP%\liuli-gfxinfo.txt
endlocal
