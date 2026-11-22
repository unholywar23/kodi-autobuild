package com.boxtools.adbrescue;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Starts the foreground RescueService when the box finishes booting.
 * On API 34 a BOOT_COMPLETED receiver is allowed to start a
 * foregroundServiceType="connectedDevice" FGS (same path the installed
 * ADB Auto-Enable app uses).
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            Intent svc = new Intent(context, RescueService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(svc);
            } else {
                context.startService(svc);
            }
        } catch (Throwable t) {
            // Never crash the system broadcast dispatcher.
        }
    }
}
