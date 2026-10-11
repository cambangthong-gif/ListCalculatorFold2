package com.redmagic.shouldervolume;

import android.content.*;
import android.media.AudioManager;

public final class TriggerReceiver extends BroadcastReceiver {
    public static final String ACTION = "com.redmagic.shouldervolume.TRIGGER";
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        String key = intent.getStringExtra("key");
        if (key == null) return;
        long now = System.currentTimeMillis();
        if ("PING".equals(key)) {
            context.getSharedPreferences(TriggerService.PREF, Context.MODE_PRIVATE)
                    .edit().putLong("last_bridge", now).apply();
            return;
        }
        if (!TriggerService.isEnabled(context)) return;
        final int direction;
        if ("LEFT".equals(key)) direction = AudioManager.ADJUST_LOWER;
        else if ("RIGHT".equals(key)) direction = AudioManager.ADJUST_RAISE;
        else return;
        Context app = context.getApplicationContext();
        android.content.SharedPreferences p =
                app.getSharedPreferences(TriggerService.PREF, Context.MODE_PRIVATE);
        int step = Math.max(1, Math.min(3, p.getInt("step", 1)));
        // App-issued calls need no Accessibility permission.
        AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audio != null) {
            try {
                for (int i = 0; i < step; i++) {
                    int flags = (i == step - 1) ? AudioManager.FLAG_SHOW_UI : 0;
                    audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, flags);
                }
            } catch (SecurityException ignored) { }
        }
        p.edit().putLong("last_key", now).putString("last_side", key).apply();
    }
}
