#!/system/bin/sh
# Experimental shell-UID bridge started by Shizuku once and detached.
# It may be killed by ROM / Shizuku stop, so check actual behavior on RM8 OS 10.
PATH=/system/bin:/system/xbin:/vendor/bin:$PATH
PID=/data/local/tmp/rm8_volume_detached.pid
LOG=/data/local/tmp/rm8_volume_detached.log
APP=com.redtrigger.volume
ACTION=com.redtrigger.volume.TRIGGER

if [ -f "$PID" ]; then
  OLD=$(cat "$PID" 2>/dev/null)
  if [ -n "$OLD" ] && kill -0 "$OLD" 2>/dev/null; then
    echo "Existing daemon PID=$OLD"
    exit 0
  fi
fi
echo "$$" > "$PID"
trap 'rm -f "$PID"; /system/bin/settings put global rm8_volume_daemon_running 0' EXIT
/system/bin/settings put global rm8_volume_daemon_running 1
/system/bin/settings put global rm8_volume_daemon_enabled 1
/system/bin/am broadcast --user 0 -a "$ACTION" -n "$APP/com.redtrigger.VolumeReceiver" --es side PING >/dev/null 2>&1
echo "Detached shoulder sensor reader starting PID=$$"

# getevent -ql reports KEY_F7 and KEY_F8 on confirmed RedTrigger devices.
# event names and permissions must still be validated on RedMagic 8 Pro OS 10.
# Keep consumer simple; no event injection, no Game Space.
while true; do
  /system/bin/getevent -ql 2>>"$LOG" | while IFS= read -r line; do
    case "$line" in
      *KEY_F7*DOWN*|*KEY_F7*00000001*) SIDE=LEFT ;;
      *KEY_F8*DOWN*|*KEY_F8*00000001*) SIDE=RIGHT ;;
      *) continue ;;
    esac
    ENABLED=$(/system/bin/settings get global rm8_volume_daemon_enabled 2>/dev/null)
    if [ "$ENABLED" = "1" ]; then
      /system/bin/am broadcast --user 0 -a "$ACTION" \
        -n "$APP/com.redtrigger.VolumeReceiver" --es side "$SIDE" >/dev/null 2>&1
    fi
  done
  sleep 1
done
