package com.redmagic.shouldervolume;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.os.*;
import android.provider.Settings;

public final class TriggerService extends Service {
    public static final String PREF = "trigger_pref";
    private static final String CHANNEL = "rm8_trigger";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ContentObserver observer;

    public static boolean isEnabled(Context c) {
        return c.getSharedPreferences(PREF, MODE_PRIVATE).getBoolean("enabled", false);
    }
    public static boolean canWrite(Context c) {
        return c.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }
    public static void setEnabled(Context c, boolean enabled) {
        c.getSharedPreferences(PREF, MODE_PRIVATE).edit().putBoolean("enabled", enabled).apply();
        if (enabled) {
            Intent i = new Intent(c, TriggerService.class);
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
            else c.startService(i);
        } else {
            c.stopService(new Intent(c, TriggerService.class));
        }
    }

    private void applyGameScene() {
        if (!isEnabled(this) || !canWrite(this)) return;
        try {
            if (Settings.Global.getInt(getContentResolver(), "nubia_game_scene", 0) != 1) {
                Settings.Global.putInt(getContentResolver(), "nubia_game_scene", 1);
            }
        } catch (SecurityException ignored) {
            // Missing ADB-granted WRITE_SECURE_SETTINGS.
        }
    }
    private final Runnable watchdog = new Runnable() {
        @Override public void run() {
            applyGameScene();
            handler.postDelayed(this, 2000L);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Nút vai RedMagic",
                    NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL)
                : new Notification.Builder(this);
        b.setContentTitle("Nút vai RedMagic đang bật")
                .setContentText("Giữ cảm biến vai sẵn sàng; cầu nối ADB cần được chạy")
                .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
                .setOngoing(true)
                .setContentIntent(PendingIntent.getActivity(this, 0,
                        new Intent(this, MainActivity.class),
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        startForeground(10, b.build());
        observer = new ContentObserver(handler) {
            @Override public void onChange(boolean selfChange) { applyGameScene(); }
        };
        getContentResolver().registerContentObserver(
                Settings.Global.getUriFor("nubia_game_scene"), false, observer);
        handler.post(watchdog);
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!isEnabled(this)) { stopSelf(); return START_NOT_STICKY; }
        applyGameScene();
        return START_STICKY;
    }
    @Override public void onDestroy() {
        handler.removeCallbacks(watchdog);
        if (observer != null) getContentResolver().unregisterContentObserver(observer);
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
