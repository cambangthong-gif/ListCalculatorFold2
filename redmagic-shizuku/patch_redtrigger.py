#!/usr/bin/env python3
"""Patch MIT licensed RedTrigger into a separate Shizuku volume app."""
import sys
from pathlib import Path
base=Path(sys.argv[1]).resolve() if len(sys.argv)>1 else Path('redtrigger-upstream').resolve()
def change(path,old,new):
    p=base/path
    src=p.read_text(encoding='utf-8')
    assert old in src,(path,old[:100])
    p.write_text(src.replace(old,new),encoding='utf-8')
def f(name): return f'app/src/main/kotlin/com/redtrigger/{name}.kt'

change('app/build.gradle.kts','applicationId = "com.redtrigger"','applicationId = "com.redtrigger.volume"')
change('app/build.gradle.kts','versionName = "3.0.15"','versionName = "0.2-shizuku-volume"')
change('app/build.gradle.kts','versionCode = 50','versionCode = 2')
change('app/build.gradle.kts','tasks.named("preBuild") {\n    dependsOn("buildNativeBinary")\n}','// Native gamepad injector unnecessary for volume')
change('app/build.gradle.kts','ndkVersion = "26.1.10909125"','// NDK not necessary')
change(f('InputReader'),'ComponentName("com.redtrigger", InputService::class.java.name)',
       'ComponentName("com.redtrigger.volume", InputService::class.java.name)')
change(f('InputService'),'"pm", "path", "com.redtrigger"',
       '"pm", "path", "com.redtrigger.volume"')
change(f('InputService'),'"pm", "grant", "com.redtrigger", permission',
       '"pm", "grant", "com.redtrigger.volume", permission')
change(f('InputReader'),'@Volatile var injectionEnabled = true',
       '@Volatile var injectionEnabled = false')
change(f('InputReader'),
       '    fun setInjectionEnabledLive(enabled: Boolean) {\n        injectionEnabled = enabled',
       '    fun setInjectionEnabledLive(enabled: Boolean) {\n        injectionEnabled = false')
change(f('InputReader'),'inputService?.setInjectionEnabled(enabled)',
       'inputService?.setInjectionEnabled(false)')
change(f('TriggerService'),
       'InputReader.injectionEnabled = prefs.getBoolean("capture_inject", true)',
       'InputReader.injectionEnabled = false')
old='''                InputReader.onTrigger = { trigger, isDown ->
                    if (isDown) {
                        DebugLog.log("Trigger", "${trigger.name} pressed")
                    }
                }'''
new='''                val audio = getSystemService(android.content.Context.AUDIO_SERVICE)
                    as android.media.AudioManager
                // Debounce capacitive input separately for each side.
                val lastPress = longArrayOf(0L, 0L)
                InputReader.onTrigger = { trigger, isDown ->
                    if (isDown) {
                        val keyIndex = if (trigger == InputReader.Trigger.LEFT) 0 else 1
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastPress[keyIndex] >= 100L) {
                            lastPress[keyIndex] = now
                            val direction = if (keyIndex == 0)
                                android.media.AudioManager.ADJUST_LOWER
                            else
                                android.media.AudioManager.ADJUST_RAISE
                            try {
                                audio.adjustStreamVolume(
                                    android.media.AudioManager.STREAM_MUSIC,
                                    direction,
                                    android.media.AudioManager.FLAG_SHOW_UI
                                )
                                DebugLog.log("Volume", "${trigger.name} pressed")
                            } catch (e: Exception) {
                                DebugLog.log("Volume", "Failed: ${e.message}")
                            }
                        }
                    }
                }'''
change(f('TriggerService'),old,new)
change('app/src/main/AndroidManifest.xml','android:label="RedTrigger"','android:label="Nút Vai Âm Lượng"')
for cls in ('MainActivity','BootReceiver','TriggerService','TriggerTileService'):
    change('app/src/main/AndroidManifest.xml',f'android:name=".{cls}"',f'android:name="com.redtrigger.{cls}"')
change('app/src/main/AndroidManifest.xml','android:label="Red Trigger"','android:label="Nút Vai"')
ui='app/src/main/kotlin/com/redtrigger/ui/MainScreen.kt'
change(ui,'text = "🎮 RedTrigger",','text = "🔊 RedMagic Nút Vai",')
change(ui,'mutableStateOf(prefs.getBoolean("capture_inject", true))','mutableStateOf(false)')
change(ui,'text = "Remap to Gamepad",','text = "Điều khiển âm lượng (không gamepad)",')
change(ui,'''                            Switch(
                                checked = injectEnabled,
                                onCheckedChange = { enabled ->''',
          '''                            Switch(
                                checked = false,
                                enabled = false,
                                onCheckedChange = { enabled ->''')
change(ui,'text = "Enable Triggers",','text = "Bật nút vai",')
change(ui,'text = "Settings",','text = "Thiết lập",')
change(ui,'text = "How remapping works",','text = "Chế độ riêng",')
change(ui,'text = "Recommended: Keep ON unless you have a specific reason to use raw keys.",',
       'text = "Nút trái giảm âm, nút phải tăng âm. Không cần KeyMapper.",')
change(ui,'text = "RedTrigger",','text = "RedMagic Nút Vai",')
print('Custom Shizuku volume variant patched; ID=com.redtrigger.volume')
