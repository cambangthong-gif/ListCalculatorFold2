#!/usr/bin/env python3
from pathlib import Path
import shutil,sys
base=Path(sys.argv[1]).resolve()
def patch(rel,a,b):
 p=base/rel
 s=p.read_text(encoding="utf-8")
 if a not in s: raise Exception("Missing anchor "+rel+": "+a[:80])
 p.write_text(s.replace(a,b),encoding="utf-8")
def k(n):return "app/src/main/kotlin/com/redtrigger/"+n+".kt"
patch("app/build.gradle.kts",'versionName = "0.2-shizuku-volume"','versionName = "0.3-shizuku-once"')
patch("app/build.gradle.kts","versionCode = 2","versionCode = 3")
patch("app/src/main/aidl/com/redtrigger/IInputService.aidl",
      "    /** Destroy the service. */",
      "    /** Start experimental detached shell reader. */\n    String startDetachedDaemon();\n\n    /** Destroy the service. */")
(base/"app/src/main/assets").mkdir(parents=True, exist_ok=True)
shutil.copyfile(Path(__file__).parent/"rm8_volume_detached.sh",base/"app/src/main/assets/rm8_volume_detached.sh")
receiver='''package com.redtrigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.SystemClock
import android.provider.Settings

class VolumeReceiver : BroadcastReceiver() {
    companion object { private val lastPress = longArrayOf(0L, 0L) }
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != "com.redtrigger.volume.TRIGGER") return
        val side = intent.getStringExtra("side") ?: return
        val prefs = context.getSharedPreferences("RedTriggerPrefs", Context.MODE_PRIVATE)
        if (side == "PING") {
            prefs.edit().putLong("daemon_ping",System.currentTimeMillis()).apply()
            return
        }
        if (Settings.Global.getInt(context.contentResolver,
                "rm8_volume_daemon_enabled",0) != 1) return
        val index = when (side) { "LEFT" -> 0; "RIGHT" -> 1; else -> return }
        val now = SystemClock.elapsedRealtime()
        synchronized(lastPress) {
            if (now - lastPress[index] < 110L) return
            lastPress[index] = now
        }
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                if (index == 0) AudioManager.ADJUST_LOWER else AudioManager.ADJUST_RAISE,
                AudioManager.FLAG_SHOW_UI)
            prefs.edit().putLong("daemon_key_time",System.currentTimeMillis())
                .putString("daemon_key_side",side).apply()
        } catch (e: Exception) {
            prefs.edit().putString("daemon_error",e.message).apply()
        }
    }
}
'''
(base/k("VolumeReceiver")).write_text(receiver,encoding="utf-8")
patch("app/src/main/AndroidManifest.xml",
      '        <receiver\n            android:name="com.redtrigger.BootReceiver"',
      '''        <receiver android:name="com.redtrigger.VolumeReceiver"
            android:exported="true" android:permission="android.permission.DUMP">
            <intent-filter>
                <action android:name="com.redtrigger.volume.TRIGGER"/>
            </intent-filter>
        </receiver>

        <receiver
            android:name="com.redtrigger.BootReceiver"''')
method='''    override fun startDetachedDaemon(): String {
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("pm","path","com.redtrigger.volume"))
            val apk = proc.inputStream.bufferedReader().readLine()
                ?.removePrefix("package:")?.trim().orEmpty()
            proc.waitFor()
            if (!apk.startsWith("/data/")) return "ERROR: APK not found"
            val dest = "/data/local/tmp/rm8_volume_detached.sh"
            val extract = "unzip -p '" + apk + "' assets/rm8_volume_detached.sh > '" +
                dest + "' && chmod 700 '" + dest + "'"
            val unzip = Runtime.getRuntime().exec(arrayOf("sh","-c",extract))
            val error = unzip.errorStream.bufferedReader().readText()
            if (unzip.waitFor()!=0) return "ERROR extracting daemon: " + error
            Runtime.getRuntime().exec(arrayOf("settings","put","global",
                "rm8_volume_daemon_enabled","1")).waitFor()
            val cmd = "if command -v setsid >/dev/null 2>&1; then " +
                "setsid /system/bin/sh '" + dest + "'; else " +
                "nohup /system/bin/sh '" + dest + "'; fi " +
                "</dev/null >/data/local/tmp/rm8_volume_detached.log 2>&1 &"
            val p = Runtime.getRuntime().exec(arrayOf("sh","-c",cmd))
            val stderr = p.errorStream.bufferedReader().readText()
            if (p.waitFor()!=0) "ERROR: " + stderr
            else "Đã gửi lệnh. Tắt Shizuku rồi thử hai nút vai."
        } catch (e: Exception) { "ERROR: " + e.message }
    }

'''
patch(k("InputService"),"    override fun destroy() {",method+"    override fun destroy() {")
patch(k("InputReader"),
      '''                inputService?.startReading(triggerCallback)
                DebugLog.log("InputReader", "Reading started on $devices")''',
      '''                // Start standalone shell service instead of keeping Shizuku input loop.
                val message = inputService?.startDetachedDaemon() ?: "ERROR: no service"
                DebugLog.log("Detached", message)
                if (message.startsWith("ERROR")) {
                    inputService?.startReading(triggerCallback)
                    DebugLog.log("Detached", "Fallback: Shizuku reader")
                }''')
patch(k("InputReader"),
      '''    fun stop() {
        try { inputService?.stopReading() }''',
      '''    fun startDetachedNow(): String {
        return try {
            val service = inputService ?: return "Cần bật nút vai, Shizuku chưa kết nối"
            service.stopReading()
            val result = service.startDetachedDaemon()
            DebugLog.log("Detached",result)
            result
        } catch(e: Exception) { "Lỗi: " + e.message }
    }

    fun stop() {
        try { inputService?.stopReading() }''')
patch(k("TriggerManager"),
      '            Settings.Global.putInt(context.contentResolver, NUBIA_GAME_SCENE, 1)',
      '''            Settings.Global.putInt(context.contentResolver,"rm8_volume_daemon_enabled",1)
            Settings.Global.putInt(context.contentResolver, NUBIA_GAME_SCENE, 1)''')
patch(k("TriggerManager"),
      '            Settings.Global.putInt(context.contentResolver, NUBIA_GAME_SCENE, 0)',
      '''            Settings.Global.putInt(context.contentResolver,"rm8_volume_daemon_enabled",0)
            Settings.Global.putInt(context.contentResolver, NUBIA_GAME_SCENE, 0)''')
ui="app/src/main/kotlin/com/redtrigger/ui/MainScreen.kt"
card='''                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Thử tắt Shizuku sau khi bật nút",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold)
                        Text("Đây là thử nghiệm trên OS 10. Bật Shizuku, bật nút vai, " +
                            "sau đó thử dừng Shizuku. Tiến trình có thể bị hệ thống dừng. " +
                            "Sau khi khởi động máy cần kích hoạt lại.",
                            style = MaterialTheme.typography.bodySmall)
                        val diagnostics = context.getSharedPreferences(
                            "RedTriggerPrefs", Context.MODE_PRIVATE)
                        val lastPing = remember(statusTick) {
                            diagnostics.getLong("daemon_ping",0L) }
                        val lastKey = remember(statusTick) {
                            diagnostics.getLong("daemon_key_time",0L) }
                        Text("Cầu nối: " + (if (lastPing > 0) "Đã khởi động"
                            else "Chưa ghi nhận") +
                            "  |  Nút cuối: " + (if (lastKey > 0)
                                java.text.DateFormat.getTimeInstance()
                                    .format(java.util.Date(lastKey)) else "Chưa có"),
                            style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = {
                                Toast.makeText(context,InputReader.startDetachedNow(),
                                    Toast.LENGTH_LONG).show()
                            },modifier = Modifier.fillMaxWidth()) {
                            Text("Khởi chạy tiến trình riêng")
                        }
                        Text("Tắt 'Bật nút vai' để vô hiệu hóa điều chỉnh âm lượng.",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }

'''
patch(ui,'                // ── Tools ──',card+'                // ── Tools ──')
print("v0.3 patch complete")
