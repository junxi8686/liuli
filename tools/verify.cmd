@echo off
REM Liuli smoke test: build the release APK, install it on the attached device,
REM launch it, screenshot it, and fail loudly if it crashed on startup.
REM
REM   tools\verify.cmd            build + install + launch + screenshot
REM   tools\verify.cmd noshot     skip the screenshot
REM
REM Screenshots land in docs\verify\.
setlocal enabledelayedexpansion
set "ROOT=C:\Users\junxi\Desktop\Android-Toolchain"
set "JAVA_HOME=%ROOT%\03-jdk\jdk-21.0.12.1+1"
set "ANDROID_HOME=%ROOT%\01-android-sdk"
set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
set "GRADLE_USER_HOME=%ROOT%\04-gradle\gradle-home"
set "ANDROID_USER_HOME=%ROOT%\06-android-user-home"
set "PATH=%JAVA_HOME%\bin;%ANDROID_HOME%\platform-tools;%ROOT%\05-git\mingit\cmd;%PATH%"

set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"
set "PROJ=%~dp0.."
set "PKG=com.liuli.btchat"
set "SHOTDIR=%PROJ%\docs\verify"

echo === 1/5 build release ===
pushd "%PROJ%"
call "%ROOT%\04-gradle\dist\gradle-9.6.0\bin\gradle.bat" assembleRelease --console=plain -q
if errorlevel 1 (echo BUILD FAILED & popd & exit /b 1)
popd

set "APK=%PROJ%\app\build\outputs\apk\release\app-release.apk"
if not exist "%APK%" (echo APK missing: %APK% & exit /b 1)

echo === 2/5 device ===
"%ADB%" get-state >nul 2>&1
if errorlevel 1 (echo no device attached & exit /b 1)

echo === 3/5 install ===
"%ADB%" install -r "%APK%" | findstr /C:"Success"
if errorlevel 1 (echo install failed & exit /b 1)

echo === 4/5 launch ===
"%ADB%" logcat -c
"%ADB%" shell am force-stop %PKG%
"%ADB%" shell am start -n %PKG%/.MainActivity >nul
timeout /t 6 /nobreak >nul

echo === 5/5 crash check ===
set "CRASH="
for /f "delims=" %%L in ('"%ADB%" logcat -d -t 400 ^| findstr /C:"FATAL EXCEPTION" /C:"AndroidRuntime: Process: %PKG%"') do set "CRASH=1"
if defined CRASH (
  echo --- CRASH DETECTED ---
  "%ADB%" logcat -d -t 400 | findstr /C:"AndroidRuntime" /C:"Caused by" /C:"at com.liuli"
  exit /b 2
)
echo no crash on startup

if /I not "%~1"=="noshot" (
  if not exist "%SHOTDIR%" mkdir "%SHOTDIR%"
  for /f "tokens=1-4 delims=/: " %%a in ("%TIME%") do set "TS=%%a%%b%%c"
  "%ADB%" shell screencap -p /sdcard/liuli_verify.png
  "%ADB%" pull /sdcard/liuli_verify.png "%SHOTDIR%\screen_!TS!.png" >nul
  echo screenshot: %SHOTDIR%\screen_!TS!.png
)

echo OK
exit /b 0
