@echo off
set "JAVA_HOME=%~dp0jdk-17"
echo Using JAVA_HOME=%JAVA_HOME%

echo Deleting build directories...
rmdir /s /q app\build
rmdir /s /q build

echo Cleaning with Gradle...
call gradle-bin\gradle-8.7\bin\gradle.bat clean

echo Compiling Debug APK...
call gradle-bin\gradle-8.7\bin\gradle.bat assembleDebug
if %errorlevel% neq 0 (
    echo Build failed!
    pause
    exit /b %errorlevel%
)
echo Build complete.

echo If a device is connected, installing...
adb install -r app\build\outputs\apk\debug\app-debug.apk
if %errorlevel% neq 0 (
    echo Installation failed or no device connected.
) else (
    echo Launching app...
    adb shell am start -n com.example.aquiethours/.MainActivity
)
pause
