#!/usr/bin/env python3
"""Apply v0.4 configurable haptic/actions patch after v0.2 and v0.3 patches."""
from pathlib import Path
import sys, shutil
base=Path(sys.argv[1]).resolve()
src=Path(__file__).parent/"v04"
def patch(path, old, new):
    file=base/path
    s=file.read_text(encoding="utf-8")
    if old not in s:
        raise RuntimeError("Anchor absent: "+path+" => "+old[:80])
    file.write_text(s.replace(old,new),encoding="utf-8")
def copy(name,path):
    out=base/path
    out.parent.mkdir(parents=True,exist_ok=True)
    shutil.copyfile(src/name,out)
def k(name): return "app/src/main/kotlin/com/redtrigger/"+name+".kt"
patch("app/build.gradle.kts",'versionName = "0.3-shizuku-once"',
      'versionName = "0.4-haptic-actions"')
patch("app/build.gradle.kts","versionCode = 3","versionCode = 4")
patch("app/src/main/AndroidManifest.xml",
      '<uses-permission android:name="android.permission.WRITE_SECURE_SETTINGS" />',
      '<uses-permission android:name="android.permission.WRITE_SECURE_SETTINGS" />\n'+
      '    <uses-permission android:name="android.permission.VIBRATE" />')
patch("app/src/main/AndroidManifest.xml",
      '        <service\n            android:name="com.redtrigger.TriggerService"',
      '''        <service
            android:name="com.redtrigger.ActionAccessibilityService"
            android:exported="true"
            android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
            <intent-filter>
                <action android:name="android.accessibilityservice.AccessibilityService"/>
            </intent-filter>
            <meta-data android:name="android.accessibilityservice"
                android:resource="@xml/shoulder_accessibility"/>
        </service>

        <service
            android:name="com.redtrigger.TriggerService"''')
text_path="app/src/main/res/values/strings.xml"
patch(text_path,"</resources>",
      '    <string name="shoulder_accessibility_description">'+
      'Dùng hai nút vai để Quay lại, Trang chủ và mở bảng thông báo. Không đọc nội dung màn hình.'+
      '</string>\n</resources>')
copy("TriggerActionEngine.kt",k("TriggerActionEngine"))
copy("VolumeReceiver.kt",k("VolumeReceiver"))
copy("ActionAccessibilityService.kt",k("ActionAccessibilityService"))
copy("TriggerCustomSettings.kt",
     "app/src/main/kotlin/com/redtrigger/ui/TriggerCustomSettings.kt")
copy("shoulder_accessibility.xml",
     "app/src/main/res/xml/shoulder_accessibility.xml")
# Plug the engine into Shizuku fallback, not the old fixed-volume callback.
p=base/k("TriggerService")
s=p.read_text(encoding="utf-8")
start=s.find("                InputReader.onTrigger = { trigger, isDown ->")
end=s.find("                InputReader.init(this)",start)
if start<0 or end<=start:
    raise RuntimeError("InputReader callback anchors absent")
p.write_text(s[:start]+
    "                InputReader.onTrigger = { trigger, isDown ->\n"+
    "                    TriggerActionEngine.onEvent(this, trigger.name, isDown)\n"+
    "                }\n"+s[end:],encoding="utf-8")
# Add press-release forwarding to shell daemon.
p=base/"app/src/main/assets/rm8_volume_detached.sh"
s=p.read_text(encoding="utf-8")
start=s.index('    case "$line" in')
end=s.index("    ENABLED=",start)
s=s[:start]+'''    case "$line" in
      *KEY_F7*DOWN*|*KEY_F7*00000001*) SIDE=LEFT; EDGE=DOWN ;;
      *KEY_F7*UP*|*KEY_F7*00000000*) SIDE=LEFT; EDGE=UP ;;
      *KEY_F8*DOWN*|*KEY_F8*00000001*) SIDE=RIGHT; EDGE=DOWN ;;
      *KEY_F8*UP*|*KEY_F8*00000000*) SIDE=RIGHT; EDGE=UP ;;
      *) continue ;;
    esac
'''+s[end:]
s=s.replace('--es side "$SIDE" >/dev/null 2>&1',
            '--es side "$SIDE" --es edge "$EDGE" >/dev/null 2>&1')
p.write_text(s,encoding="utf-8")
patch("app/src/main/kotlin/com/redtrigger/ui/MainScreen.kt",
      "                // ── Tools ──",
      "                TriggerCustomSettings()\n\n                // ── Tools ──")
print("v0.4 haptics and per-side tap/double/hold actions prepared")
