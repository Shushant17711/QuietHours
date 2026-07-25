@echo off
set "JAVA_HOME=%~dp0jdk-17"
echo Using JAVA_HOME=%JAVA_HOME%
echo Compiling Debug APK...
call gradle-bin\gradle-8.7\bin\gradle.bat assembleDebug --stacktrace
if %errorlevel% neq 0 (
    echo Build failed!
    pause
    exit /b %errorlevel%
)
echo Build complete.
pause
