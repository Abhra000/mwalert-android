package com.mwalert.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Set;

public class PollingService extends Service {

    private static final String CHANNEL_ID_FOREGROUND = "mwalert_foreground";
    private static final String CHANNEL_ID_ALERTS = "mwalert_alerts";
    private static final int FOREGROUND_NOTIFICATION_ID = 1;
    private static final long POLL_INTERVAL_MS = 6000; // 6 sec

    private Handler handler;
    private Runnable pollRunnable;
    private PowerManager.WakeLock wakeLock;
    private SharedPreferences prefs;

    // Track which job IDs we've already notified about (so we don't re-notify)
    private final Set<Integer> notifiedJobIds = new HashSet<>();
    private boolean firstPoll = true;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        createNotificationChannels();
        acquireWakeLock();

        handler = new Handler();
        pollRunnable = this::pollAndReschedule;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Promote to foreground service immediately
        startForeground(FOREGROUND_NOTIFICATION_ID, buildForegroundNotification());

        // Start polling
        handler.removeCallbacks(pollRunnable);
        handler.post(pollRunnable);

        // If service is killed by system, restart it automatically
        return START_STICKY;
    }

    private void pollAndReschedule() {
        new Thread(this::poll).start();
        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
    }

    private void poll() {
        String token = prefs.getString(MainActivity.KEY_TOKEN, "");
        String server = prefs.getString(MainActivity.KEY_SERVER, "");
        if (token.isEmpty() || server.isEmpty()) return;

        String apiBase = ApiHelper.buildApiBase(server);

        try {
            String resp = ApiHelper.get(apiBase + "/api/jobs", token);
            JSONObject json = new JSONObject(resp);
            JSONArray jobs = json.optJSONArray("jobs");
            if (jobs == null) return;

            // Build set of current IDs
            Set<Integer> currentIds = new HashSet<>();
            for (int i = 0; i < jobs.length(); i++) {
                currentIds.add(jobs.getJSONObject(i).optInt("id", -1));
            }

            // On first poll, just record everything as "already seen" — no notifications
            // (otherwise user would get spammed by old jobs every time the service restarts)
            if (firstPoll) {
                notifiedJobIds.addAll(currentIds);
                firstPoll = false;
                return;
            }

            // Find new IDs and notify
            for (int i = 0; i < jobs.length(); i++) {
                JSONObject j = jobs.getJSONObject(i);
                int id = j.optInt("id", -1);
                if (id < 0) continue;
                if (!notifiedJobIds.contains(id)) {
                    notifiedJobIds.add(id);
                    showJobNotification(j);
                }
            }
        } catch (Exception e) {
            // silent fail — try again next poll
        }
    }

    private void showJobNotification(JSONObject j) {
        String title = j.optString("title", "New job");
        String payment = j.optString("payment", "?");
        String url = j.optString("url", "");

        Intent openAppIntent = new Intent(this, MainActivity.class);
        openAppIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID_ALERTS)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("🔔 New Job: $" + payment)
                .setContentText(title.length() > 100 ? title.substring(0, 100) + "..." : title)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(title))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVibrate(new long[]{0, 300, 100, 300, 100, 600})
                .setSound(sound)
                .setLights(0xFF00F2FE, 1000, 500)
                .setAutoCancel(true)
                .setContentIntent(pi);

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        // Use unique ID per job so multiple alerts stack
        int notifId = (int) (System.currentTimeMillis() & 0x7FFFFFFF);
        nm.notify(notifId, b.build());
    }

    private Notification buildForegroundNotification() {
        Intent openAppIntent = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID_FOREGROUND)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("MW Alert is monitoring")
                .setContentText("Watching Microworkers for new jobs (every 6s)")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setSilent(true)
                .setContentIntent(pi)
                .build();
    }

    private void createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

            // Foreground service channel (silent)
            NotificationChannel fgChannel = new NotificationChannel(
                    CHANNEL_ID_FOREGROUND, "Background Service",
                    NotificationManager.IMPORTANCE_LOW);
            fgChannel.setDescription("Keeps MW Alert running 24/7");
            fgChannel.setSound(null, null);
            fgChannel.enableLights(false);
            fgChannel.enableVibration(false);
            nm.createNotificationChannel(fgChannel);

            // Alerts channel (high priority + sound + vibrate)
            NotificationChannel alertChannel = new NotificationChannel(
                    CHANNEL_ID_ALERTS, "Job Alerts",
                    NotificationManager.IMPORTANCE_HIGH);
            alertChannel.setDescription("Notifications when matching jobs are found");
            alertChannel.enableLights(true);
            alertChannel.setLightColor(Color.CYAN);
            alertChannel.enableVibration(true);
            alertChannel.setVibrationPattern(new long[]{0, 300, 100, 300, 100, 600});

            AudioAttributes audioAttrs = new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .build();
            Uri sound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
            alertChannel.setSound(sound, audioAttrs);

            nm.createNotificationChannel(alertChannel);
        }
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MWAlert::PollingWakeLock");
            wakeLock.acquire();
        } catch (Exception e) {
            // ignore
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(pollRunnable);
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        // Try to restart ourselves
        Intent restartIntent = new Intent(this, PollingService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(restartIntent);
        } else {
            startService(restartIntent);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
