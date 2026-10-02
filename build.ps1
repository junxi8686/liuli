# Liuli — Android build helper.
# Pins JDK / SDK / Gradle to the toolchain folder on the Desktop so no global
# install is needed. Usage:
#   .\build.ps1 assembleDebug
#   .\build.ps1 assembleRelease
#   .\build.ps1 testDebugUnitTest
#   .\build.ps1 :app:compileDebugKotlin --offline
param(
    [Parameter(Position = 0, ValueFromRemainingArguments = $true)]
    [string[]] $GradleArgs = @("assembleDebug")
)

$root = "C:\Users\junxi\Desktop\Android-Toolchain"
$env:JAVA_HOME        = "$root\03-jdk\jdk-21.0.12.1+1"
$env:ANDROID_HOME     = "$root\01-android-sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:GRADLE_USER_HOME = "$root\04-gradle\gradle-home"
$env:ANDROID_USER_HOME = "$root\06-android-user-home"
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$root\05-git\mingit\cmd;$env:Path"

$gradle = "$root\04-gradle\dist\gradle-9.6.0\bin\gradle.bat"

# Give every build directory its own Gradle project cache.
#
# `-PliuliBuildDir=<name>` sends outputs to separate folders, but Gradle's task
# history lives in the *project* `.gradle` directory and is shared. Two people
# building at once therefore trip over each other: the second run loads a
# history that registered another folder's outputs, decides they are stale, and
# tries to delete files the first run is still writing. On Windows that is a
# file lock, and the build dies with
#   "Failed to clean up output files for task ':app:processDebugResources'"
# which says nothing about the real cause.
#
# Deriving the cache directory from the same property keeps parallel builds and
# the shared caches (in GRADLE_USER_HOME) working, and removes a failure mode
# that looks like a code error but is not.
$buildDirArg = $GradleArgs | Where-Object { $_ -like '-PliuliBuildDir=*' } | Select-Object -First 1
$projectCache = if ($buildDirArg) {
    $name = ($buildDirArg -split '=', 2)[1]
    Join-Path $PSScriptRoot ".pcache\$name"
} else {
    Join-Path $PSScriptRoot ".pcache\default"
}
New-Item -ItemType Directory -Force -Path $projectCache | Out-Null

& $gradle @GradleArgs "--project-cache-dir=$projectCache" --console=plain
exit $LASTEXITCODE
