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

public class PollingService extends Service {

    private static final String CHANNEL_ID_FOREGROUND = "mwalert_foreground";
    // Channel for alerts — versioned so we can recreate when sound/vibrate settings change
    private static final String CHANNEL_ID_ALERTS_PREFIX = "mwalert_alerts_v";
    private static final int FOREGROUND_NOTIFICATION_ID = 1;
    private static final long POLL_INTERVAL_MS = 6000;             // 6 sec
    private static final long URL_REFRESH_INTERVAL_MS = 300_000;   // 5 min

    // CONFIG SOURCE — must match MainActivity.CONFIG_URL
    private static final String CONFIG_URL = "https://mw-alert.netlify.app/config.json";

    private Handler handler;
    private Runnable pollRunnable;
    private Runnable urlRefreshRunnable;
    private PowerManager.WakeLock wakeLock;
    private SharedPreferences prefs;

    private boolean firstPoll = true;
    private String currentAlertChannelId;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        createForegroundChannel();
        ensureAlertChannel();
        acquireWakeLock();

        handler = new Handler();
        pollRunnable = this::pollAndReschedule;
        urlRefreshRunnable = this::refreshUrlAndReschedule;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Re-check alert channel each time service starts (in case user changed settings)
        ensureAlertChannel();

        startForeground(FOREGROUND_NOTIFICATION_ID, buildForegroundNotification());

        handler.removeCallbacks(pollRunnable);
        handler.post(pollRunnable);

        handler.removeCallbacks(urlRefreshRunnable);
        handler.postDelayed(urlRefreshRunnable, URL_REFRESH_INTERVAL_MS);

        return START_STICKY;
    }

    private void pollAndReschedule() {
        new Thread(this::poll).start();
        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
    }

    private void refreshUrlAndReschedule() {
        new Thread(this::refreshUrl).start();
        handler.postDelayed(urlRefreshRunnable, URL_REFRESH_INTERVAL_MS);
    }

    private void refreshUrl() {
        try {
            String resp = ApiHelper.getRaw(CONFIG_URL);
            JSONObject json = new JSONObject(resp);
            String server = json.optString("server", "").trim();
            if (server.length() > 0) {
                String saved = prefs.getString(MainActivity.KEY_SERVER, "");
                if (!server.equals(saved)) {
                    prefs.edit().putString(MainActivity.KEY_SERVER, server).apply();
                }
            }
        } catch (Exception ignored) {}
    }

    private void poll() {
        // Re-check alert channel — user may have changed sound while service runs
        ensureAlertChannel();

        String token = prefs.getString(MainActivity.KEY_TOKEN, "");
        String server = prefs.getString(MainActivity.KEY_SERVER, "");
        if (token.isEmpty() || server.isEmpty()) return;

        String apiBase = ApiHelper.buildApiBase(server);

        try {
            String resp = ApiHelper.get(apiBase + "/api/jobs", token);
            JSONObject json = new JSONObject(resp);
            JSONArray jobs = json.optJSONArray("jobs");
            if (jobs == null) return;

            int lastSeenId = prefs.getInt(MainActivity.KEY_LAST_SEEN_ID, -1);
            int highestIdNow = lastSeenId;

            if (firstPoll && lastSeenId == -1) {
                for (int i = 0; i < jobs.length(); i++) {
                    int id = jobs.getJSONObject(i).optInt("id", -1);
                    if (id > highestIdNow) highestIdNow = id;
                }
                prefs.edit().putInt(MainActivity.KEY_LAST_SEEN_ID, highestIdNow).apply();
                firstPoll = false;
                return;
            }
            firstPoll = false;

            for (int i = 0; i < jobs.length(); i++) {
                JSONObject j = jobs.getJSONObject(i);
                int id = j.optInt("id", -1);
                if (id < 0) continue;
                if (id > lastSeenId) {
                    showJobNotification(j);
                    if (id > highestIdNow) highestIdNow = id;
                }
            }

            if (highestIdNow > lastSeenId) {
                prefs.edit().putInt(MainActivity.KEY_LAST_SEEN_ID, highestIdNow).apply();
            }
        } catch (Exception e) {
            // silent fail — try again next poll
        }
    }

    private void showJobNotification(JSONObject j) {
        String title = j.optString("title", "New job");
        String payment = j.optString("payment", "?");

        Intent openAppIntent = new Intent(this, MainActivity.class);
        openAppIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        boolean soundEnabled = prefs.getBoolean(NotificationSettingsActivity.KEY_SOUND_ENABLED, true);
        boolean vibrateEnabled = prefs.getBoolean(NotificationSettingsActivity.KEY_VIBRATE_ENABLED, true);

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, currentAlertChannelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("🔔 New Job: $" + payment)
                .setContentText(title.length() > 100 ? title.substring(0, 100) + "..." : title)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(title))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setLights(0xFF00F2FE, 1000, 500)
                .setAutoCancel(true)
                .setContentIntent(pi);

        // Pre-Oreo: set sound/vibrate on notification (channel handles it on Oreo+)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            if (soundEnabled) {
                b.setSound(getCurrentSoundUri());
            }
            if (vibrateEnabled) {
                b.setVibrate(new long[]{0, 300, 100, 300, 100, 600});
            }
        }

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        int notifId = (int) (j.optInt("id", 0) & 0x7FFFFFFF);
        if (notifId == 0) notifId = (int) (System.currentTimeMillis() & 0x7FFFFFFF);
        nm.notify(notifId, b.build());
    }

    private Uri getCurrentSoundUri() {
        String saved = prefs.getString(NotificationSettingsActivity.KEY_SOUND_URI, null);
        if (saved != null && !saved.isEmpty()) {
            try { return Uri.parse(saved); } catch (Exception ignored) {}
        }
        return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
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

    private void createForegroundChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            NotificationChannel fgChannel = new NotificationChannel(
                    CHANNEL_ID_FOREGROUND, "Background Service",
                    NotificationManager.IMPORTANCE_LOW);
            fgChannel.setDescription("Keeps MW Alert running 24/7");
            fgChannel.setSound(null, null);
            fgChannel.enableLights(false);
            fgChannel.enableVibration(false);
            nm.createNotificationChannel(fgChannel);
        }
    }

    /**
     * Ensures the alert channel exists with current sound/vibrate settings.
     * If the user changed settings (channel version bumped), creates a new
     * channel ID and deletes the old one (Android channels are immutable).
     */
    private void ensureAlertChannel() {
        int version = prefs.getInt(NotificationSettingsActivity.KEY_CHANNEL_VERSION, 0);
        currentAlertChannelId = CHANNEL_ID_ALERTS_PREFIX + version;

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        // If channel already exists, no-op
        if (nm.getNotificationChannel(currentAlertChannelId) != null) return;

        // Delete old versioned channels to keep settings clean
        if (nm.getNotificationChannels() != null) {
            for (NotificationChannel ch : nm.getNotificationChannels()) {
                if (ch.getId().startsWith(CHANNEL_ID_ALERTS_PREFIX)
                        && !ch.getId().equals(currentAlertChannelId)) {
                    nm.deleteNotificationChannel(ch.getId());
                }
            }
        }

        // Create the fresh channel
        NotificationChannel alertChannel = new NotificationChannel(
                currentAlertChannelId, "Job Alerts",
                NotificationManager.IMPORTANCE_HIGH);
        alertChannel.setDescription("Notifications when matching jobs are found");
        alertChannel.enableLights(true);
        alertChannel.setLightColor(Color.CYAN);

        boolean vibrate = prefs.getBoolean(NotificationSettingsActivity.KEY_VIBRATE_ENABLED, true);
        alertChannel.enableVibration(vibrate);
        if (vibrate) {
            alertChannel.setVibrationPattern(new long[]{0, 300, 100, 300, 100, 600});
        }

        boolean sound = prefs.getBoolean(NotificationSettingsActivity.KEY_SOUND_ENABLED, true);
        if (sound) {
            AudioAttributes audioAttrs = new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .build();
            alertChannel.setSound(getCurrentSoundUri(), audioAttrs);
        } else {
            alertChannel.setSound(null, null);
        }

        nm.createNotificationChannel(alertChannel);
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MWAlert::PollingWakeLock");
            wakeLock.acquire();
        } catch (Exception ignored) {}
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        handler.removeCallbacks(pollRunnable);
        handler.removeCallbacks(urlRefreshRunnable);
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (prefs != null && !prefs.getString(MainActivity.KEY_TOKEN, "").isEmpty()) {
            Intent restartIntent = new Intent(this, PollingService.class);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(restartIntent);
            } else {
                startService(restartIntent);
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
