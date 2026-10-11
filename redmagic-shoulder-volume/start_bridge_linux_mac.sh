#!/bin/sh
set -eu
echo "Connect the RedMagic phone over ADB (USB or wireless) first."
adb devices
adb shell pm grant com.redmagic.shouldervolume android.permission.WRITE_SECURE_SETTINGS
adb push "$(dirname "$0")/scripts/redmagic-bridge.sh" /data/local/tmp/redmagic-bridge.sh
adb shell chmod 700 /data/local/tmp/redmagic-bridge.sh
adb shell 'nohup sh /data/local/tmp/redmagic-bridge.sh </dev/null >/data/local/tmp/rm8_shouldervolume.log 2>&1 &'
echo "Bridge start submitted. Open the app to enable triggers."
