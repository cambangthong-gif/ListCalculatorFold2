@echo off
setlocal
echo REDMAGIC 8 PRO - START SHOULDER BRIDGE
echo.
where adb >nul 2>&1
if errorlevel 1 (
  echo ERROR: adb not found. Install Google Android Platform Tools first.
  pause
  exit /b 1
)
adb devices
echo.
echo 1. Install the APK separately and open it once before continuing.
adb shell pm grant com.redmagic.shouldervolume android.permission.WRITE_SECURE_SETTINGS
if errorlevel 1 goto failed
adb push "%~dp0scripts\redmagic-bridge.sh" /data/local/tmp/redmagic-bridge.sh
if errorlevel 1 goto failed
adb shell chmod 700 /data/local/tmp/redmagic-bridge.sh
adb shell "nohup sh /data/local/tmp/redmagic-bridge.sh > /data/local/tmp/rm8_shouldervolume.log 2>&1 < /dev/null &"
echo.
echo Completed command submission. Check the app for permission and PING.
echo If PING does not appear, see the troubleshooting section in README.
pause
exit /b 0
:failed
echo ADB command failed. Check USB debugging and device authorization.
pause
exit /b 1
