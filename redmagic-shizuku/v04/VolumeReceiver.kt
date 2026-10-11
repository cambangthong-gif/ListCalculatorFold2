package com.redtrigger

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings

class VolumeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != "com.redtrigger.volume.TRIGGER") return
        val side = intent.getStringExtra("side") ?: return
        val prefs = context.getSharedPreferences("RedTriggerPrefs", Context.MODE_PRIVATE)
        if (side == "PING") {
            prefs.edit().putLong("daemon_ping", System.currentTimeMillis()).apply()
            return
        }
        if (Settings.Global.getInt(context.contentResolver,
                "rm8_volume_daemon_enabled", 0) != 1) return
        val edge = intent.getStringExtra("edge") ?: "DOWN"
        if (edge != "DOWN" && edge != "UP") return
        TriggerActionEngine.onEvent(context, side, edge == "DOWN")
        if (edge == "DOWN") {
            prefs.edit().putLong("daemon_key_time", System.currentTimeMillis())
                .putString("daemon_key_side", side).apply()
        }
    }
}
