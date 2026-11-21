@echo off
chcp 65001 >nul
rem 需要系统里配好 JAVA_HOME（JDK 25）和 ANDROID_HOME / ANDROID_SDK_ROOT

cd /d "%~dp0"

if "%JAVA_HOME%"=="" (
    echo 没找到 JAVA_HOME。请先安装 JDK 25，并把它设成环境变量。
    pause
    exit /b 1
)

echo [1/2] 编译 debug 包...
call gradlew.bat :app:assembleDebug --console=plain
if errorlevel 1 (
    echo.
    echo 编译失败。多半是依赖没下全，去掉网络限制重跑一次试试。
    pause
    exit /b 1
)

set "APK=%~dp0app\build\outputs\apk\debug\app-debug.apk"
echo.
echo [2/2] 编译成功: %APK%
echo.
echo 装到手机:
echo   adb install -r "%APK%"
echo.
pause
