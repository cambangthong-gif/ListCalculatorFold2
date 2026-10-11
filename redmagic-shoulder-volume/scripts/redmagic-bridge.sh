#!/system/bin/sh
# RedMagic 8 Pro OS 10 - experimental ADB bridge
# Runs as Android shell user. No Shizuku, Game Space or root.
# Must be restarted using ADB after reboot on ordinary stock firmware.
PATH=/system/bin:/system/xbin:/vendor/bin:$PATH
PIDFILE=/data/local/tmp/rm8_shouldervolume.pid
LOGTAG=RM8Bridge

if [ -f "$PIDFILE" ]; then
  old=$(cat "$PIDFILE" 2>/dev/null)
  if [ -n "$old" ] && kill -0 "$old" 2>/dev/null; then
    echo "Bridge already running with PID $old"
    exit 0
  fi
fi
echo $$ > "$PIDFILE"
trap 'rm -f "$PIDFILE"; exit 0' INT TERM
trap 'rm -f "$PIDFILE"' EXIT

send_key() {
  /system/bin/am broadcast \
    -a com.redmagic.shouldervolume.TRIGGER \
    -n com.redmagic.shouldervolume/.TriggerReceiver \
    --es key "$1" >/dev/null 2>&1
}
echo "Bridge started: pid=$$"
send_key PING
/system/bin/settings put global nubia_game_scene 1

# Read raw Linux input events (not Android onKeyDown).
# RedMagic 11 reports F7/F8; verify this on RM8 OS10 with adb shell getevent -l.
while true; do
  /system/bin/getevent -l 2>/dev/null | while IFS= read -r line; do
    case "$line" in
      *KEY_F7*DOWN*|*"0001 0041 00000001"*) send_key LEFT ;;
      *KEY_F8*DOWN*|*"0001 0042 00000001"*) send_key RIGHT ;;
    esac
  done
  sleep 1
done
