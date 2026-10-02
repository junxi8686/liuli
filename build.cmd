@echo off
REM Liuli Android build helper (cmd wrapper, no execution-policy issues).
REM Usage:  build.cmd assembleDebug
REM         build.cmd assembleRelease
REM         build.cmd testDebugUnitTest
setlocal
set "ROOT=C:\Users\junxi\Desktop\Android-Toolchain"
set "JAVA_HOME=%ROOT%\03-jdk\jdk-21.0.12.1+1"
set "ANDROID_HOME=%ROOT%\01-android-sdk"
set "ANDROID_SDK_ROOT=%ANDROID_HOME%"
set "GRADLE_USER_HOME=%ROOT%\04-gradle\gradle-home"
set "ANDROID_USER_HOME=%ROOT%\06-android-user-home"
set "PATH=%JAVA_HOME%\bin;%ANDROID_HOME%\platform-tools;%ROOT%\05-git\mingit\cmd;%PATH%"
if "%~1"=="" (
  call "%ROOT%\04-gradle\dist\gradle-9.6.0\bin\gradle.bat" assembleDebug --console=plain
) else (
  call "%ROOT%\04-gradle\dist\gradle-9.6.0\bin\gradle.bat" %* --console=plain
)
exit /b %ERRORLEVEL%
