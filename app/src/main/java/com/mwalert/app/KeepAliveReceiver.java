package com.mwalert.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

/**
 * Receives AlarmManager pings every 30 seconds.
 * If the user is logged in, ensures PollingService is running.
 * This guarantees notifications even when the app is fully closed.
 */
public class KeepAliveReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        SharedPreferences prefs = context.getSharedPreferences(
                MainActivity.PREFS, Context.MODE_PRIVATE);

        // Only restart if user is logged in
        String token = prefs.getString(MainActivity.KEY_TOKEN, "");
        if (token.isEmpty()) return;

        // Start (or restart) the polling service
        Intent serviceIntent = new Intent(context, PollingService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }

        // Schedule next alarm
        PollingService.scheduleKeepaliveAlarm(context);
    }
}
