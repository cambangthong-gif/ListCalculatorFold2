package com.redtrigger

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.KeyEvent

object TriggerActionEngine {
    val actions = linkedMapOf(
        "NONE" to "Không làm gì",
        "VOL_DOWN" to "Giảm âm lượng",
        "VOL_UP" to "Tăng âm lượng",
        "PLAY_PAUSE" to "Phát / Tạm dừng",
        "NEXT" to "Bài tiếp theo",
        "PREV" to "Bài trước",
        "MUTE" to "Bật / tắt tiếng",
        "BACK" to "Quay lại",
        "HOME" to "Màn hình chính",
        "RECENTS" to "Ứng dụng gần đây",
        "NOTIFICATIONS" to "Bảng thông báo",
        "QUICK_SETTINGS" to "Cài đặt nhanh"
    )

    private val handler = Handler(Looper.getMainLooper())
    private val held = booleanArrayOf(false, false)
    private val longFired = booleanArrayOf(false, false)
    private val holdJobs = arrayOfNulls<Runnable>(2)
    private val pendingTap = arrayOfNulls<Runnable>(2)

    fun onEvent(context: Context, side: String, pressed: Boolean) {
        val index = when (side) { "LEFT" -> 0; "RIGHT" -> 1; else -> return }
        handler.post { handle(context.applicationContext, index, pressed) }
    }

    private fun handle(ctx: Context, index: Int, down: Boolean) {
        val prefs = ctx.getSharedPreferences("RedTriggerPrefs", Context.MODE_PRIVATE)
        val prefix = if (index == 0) "left" else "right"
        if (down) {
            if (held[index]) return
            held[index] = true
            longFired[index] = false
            vibrate(ctx, prefs.getBoolean("haptic_enabled", true),
                prefs.getInt("haptic_duration", 16),
                prefs.getInt("haptic_strength", 130))
            val job = Runnable {
                if (held[index] && !longFired[index]) {
                    longFired[index] = true
                    pendingTap[index]?.let { handler.removeCallbacks(it) }
                    pendingTap[index] = null
                    run(ctx, prefs.getString(prefix + "_long", "NONE") ?: "NONE")
                }
            }
            holdJobs[index] = job
            handler.postDelayed(job, prefs.getInt("long_ms", 550).toLong().coerceIn(350L, 1400L))
        } else {
            if (!held[index]) return
            held[index] = false
            holdJobs[index]?.let { handler.removeCallbacks(it) }
            holdJobs[index] = null
            if (longFired[index]) return
            val earlier = pendingTap[index]
            if (earlier != null) {
                handler.removeCallbacks(earlier)
                pendingTap[index] = null
                run(ctx, prefs.getString(prefix + "_double", "NONE") ?: "NONE")
            } else {
                val job = Runnable {
                    pendingTap[index] = null
                    val fallback = if (index == 0) "VOL_DOWN" else "VOL_UP"
                    run(ctx, prefs.getString(prefix + "_tap", fallback) ?: fallback)
                }
                pendingTap[index] = job
                val hasDouble = prefs.getString(prefix + "_double", "NONE") != "NONE"
                val millis = if (hasDouble) prefs.getInt("double_ms", 280).toLong()
                    .coerceIn(180L, 500L) else 0L
                handler.postDelayed(job, millis)
            }
        }
    }

    private fun run(ctx: Context, code: String) {
        if (code == "NONE") return
        if (ActionAccessibilityService.perform(code)) return
        if (code == "BACK" || code == "HOME" || code == "RECENTS" ||
            code == "NOTIFICATIONS" || code == "QUICK_SETTINGS") {
            DebugLog.log("Action", "Bật quyền Trợ năng để dùng " + code)
            return
        }
        val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        try {
            when (code) {
                "VOL_DOWN" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                "VOL_UP" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                "MUTE" -> audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_TOGGLE_MUTE, AudioManager.FLAG_SHOW_UI)
                "PLAY_PAUSE", "NEXT", "PREV" -> {
                    val key = when (code) {
                        "PLAY_PAUSE" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                        "NEXT" -> KeyEvent.KEYCODE_MEDIA_NEXT
                        else -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
                    }
                    audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
                    audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
                }
            }
            DebugLog.log("Action", "Pressed " + code)
        } catch (error: Exception) {
            DebugLog.log("Action", "Failed " + code + ": " + error.message)
        }
    }

    private fun vibrate(ctx: Context, enabled: Boolean, duration: Int, intensity: Int) {
        if (!enabled) return
        try {
            val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31)
                ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
            else @Suppress("DEPRECATION")
                (ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
            if (v?.hasVibrator() == true) {
                v.vibrate(VibrationEffect.createOneShot(
                    duration.toLong().coerceIn(5L, 70L),
                    intensity.coerceIn(1, 255)))
            }
        } catch (error: Exception) {
            DebugLog.log("Haptic", "Lỗi rung: " + error.message)
        }
    }
}
