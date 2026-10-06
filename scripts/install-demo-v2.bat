@echo off
rem Installs ONLY the v2 build of the demo app (com.clockin.mwatestapp) over
rem whatever is currently installed, so you can switch versions by hand without
rem rebuilding or reaching for adb yourself. Never uninstalls anything and never
rem touches the tester (com.clockin.apptester) - adb install -r -d only ever
rem affects the one APK path given to it below.
setlocal

set "PROJECT_ROOT=%~dp0.."
set "APK_PATH=%PROJECT_ROOT%\app\build\outputs\apk\v2\debug\app-v2-debug.apk"

if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" (
    set "ADB=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
) else if defined ANDROID_HOME (
    set "ADB=%ANDROID_HOME%\platform-tools\adb.exe"
) else (
    echo ERROR: Could not find adb.exe.
    echo Checked %%LOCALAPPDATA%%\Android\Sdk\platform-tools\adb.exe and ANDROID_HOME is not set.
    exit /b 1
)

if not exist "%ADB%" (
    echo ERROR: adb.exe not found at "%ADB%".
    exit /b 1
)

if not exist "%APK_PATH%" (
    echo APK not found: "%APK_PATH%"
    echo Build it first with gradlew :app:assembleV2Debug and stop.
    exit /b 1
)

echo Installing demo app v2 from "%APK_PATH%" ...
"%ADB%" install -r -d "%APK_PATH%"
if errorlevel 1 (
    echo ERROR: Install failed - see adb output above.
    exit /b 1
)

echo Installed demo app v2 successfully.
endlocal
