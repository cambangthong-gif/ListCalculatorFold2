package com.redmagic.shouldervolume;

import android.content.*;
import android.os.Build;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())
                && TriggerService.isEnabled(context)
                && TriggerService.canWrite(context)) {
            Intent service = new Intent(context, TriggerService.class);
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(service);
            else context.startService(service);
        }
    }
}
