package com.mwalert.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;

import androidx.core.app.NotificationCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

import org.json.JSONObject;

import java.util.Map;

/**
 * Receives FCM pushes from the PC scraper. Works EVEN WHEN the app is closed,
 * swiped away, or the OS has aggressive battery management — Google Play Services
 * delivers the push and Android starts this service automatically.
 *
 * Token rotation is also handled here: when Firebase rotates the token, we
 * re-register it with our scraper.
 */
public class MyFirebaseMessagingService extends FirebaseMessagingService {

    private static final String CHANNEL_ID_ALERTS_PREFIX = "mwalert_alerts_v";

    /**
     * Called when FCM rotates the device token (rare, but happens).
     * Re-register with our scraper so we don't lose pushes.
     */
    @Override
    public void onNewToken(String token) {
        super.onNewToken(token);
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        // Save it so MainActivity can register on next login if user is logged out
        prefs.edit().putString(MainActivity.KEY_FCM_TOKEN, token).apply();

        // If user is currently logged in, register immediately
        String authToken = prefs.getString(MainActivity.KEY_TOKEN, "");
        String server = prefs.getString(MainActivity.KEY_SERVER, "");
        if (!authToken.isEmpty() && !server.isEmpty()) {
            new Thread(() -> {
                try {
                    String apiBase = ApiHelper.buildApiBase(server);
                    String body = new JSONObject()
                            .put("fcm_token", token)
                            .put("platform", "android")
                            .toString();
                    ApiHelper.post(apiBase + "/api/register-token", body, authToken);
                } catch (Exception ignored) {}
            }).start();
        }
    }

    /**
     * Called when an FCM push arrives. We use 'data' messages from the scraper
     * (not 'notification') so we control display — that lets us honour the
     * user's custom sound/channel settings AND wakes the app even when killed.
     */
    @Override
    public void onMessageReceived(RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);
        Map<String, String> data = remoteMessage.getData();
        if (data == null || data.isEmpty()) return;

        String notifTitle = data.getOrDefault("notif_title", "🔔 New Job");
        String notifBody = data.getOrDefault("notif_body", "");
        String fullTitle = data.getOrDefault("title", "");
        String jobIdStr = data.getOrDefault("job_id", "0");

        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);

        // Use the same versioned alert channel that PollingService uses,
        // so the user's chosen sound/vibrate from NotificationSettings applies.
        ensureAlertChannel(prefs);
        int channelVersion = prefs.getInt(NotificationSettingsActivity.KEY_CHANNEL_VERSION, 0);
        String channelId = CHANNEL_ID_ALERTS_PREFIX + channelVersion;

        boolean soundEnabled = prefs.getBoolean(NotificationSettingsActivity.KEY_SOUND_ENABLED, true);
        boolean vibrateEnabled = prefs.getBoolean(NotificationSettingsActivity.KEY_VIBRATE_ENABLED, true);

        // Tap → open app
        Intent openAppIntent = new Intent(this, MainActivity.class);
        openAppIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        // Body: prefer the pre-built notif_body, fallback to full title
        String bodyText = notifBody.isEmpty() ? fullTitle : notifBody;

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(notifTitle)
                .setContentText(bodyText.length() > 100 ? bodyText.substring(0, 100) + "..." : bodyText)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(bodyText))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setLights(0xFF00F2FE, 1000, 500)
                .setAutoCancel(true)
                .setContentIntent(pi);

        // Pre-Oreo: per-notification sound/vibrate (Oreo+ uses channel)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            if (soundEnabled) b.setSound(getCurrentSoundUri(prefs));
            if (vibrateEnabled) b.setVibrate(new long[]{0, 300, 100, 300, 100, 600});
        }

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        // Use job_id as notification ID so duplicates de-dupe automatically
        int notifId;
        try {
            notifId = (int) (Long.parseLong(jobIdStr) & 0x7FFFFFFF);
        } catch (Exception e) {
            notifId = (int) (System.currentTimeMillis() & 0x7FFFFFFF);
        }
        if (notifId == 0) notifId = (int) (System.currentTimeMillis() & 0x7FFFFFFF);

        // Track last-seen ID so PollingService (if it also fires) doesn't double-notify
        int lastSeen = prefs.getInt(MainActivity.KEY_LAST_SEEN_ID, -1);
        if (notifId > lastSeen) {
            prefs.edit().putInt(MainActivity.KEY_LAST_SEEN_ID, notifId).apply();
        }

        nm.notify(notifId, b.build());
    }

    private Uri getCurrentSoundUri(SharedPreferences prefs) {
        String saved = prefs.getString(NotificationSettingsActivity.KEY_SOUND_URI, null);
        if (saved != null && !saved.isEmpty()) {
            try { return Uri.parse(saved); } catch (Exception ignored) {}
        }
        return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
    }

    /**
     * Make sure the alert channel exists with current sound/vibrate settings
     * before posting a notification. (Same logic as PollingService — this is
     * called when a push arrives even if PollingService never ran this session.)
     */
    private void ensureAlertChannel(SharedPreferences prefs) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;

        int version = prefs.getInt(NotificationSettingsActivity.KEY_CHANNEL_VERSION, 0);
        String channelId = CHANNEL_ID_ALERTS_PREFIX + version;

        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm.getNotificationChannel(channelId) != null) return;

        NotificationChannel alertChannel = new NotificationChannel(
                channelId, "Job Alerts",
                NotificationManager.IMPORTANCE_HIGH);
        alertChannel.setDescription("Notifications when matching jobs are found");
        alertChannel.enableLights(true);
        alertChannel.setLightColor(Color.CYAN);

        boolean vibrate = prefs.getBoolean(NotificationSettingsActivity.KEY_VIBRATE_ENABLED, true);
        alertChannel.enableVibration(vibrate);
        if (vibrate) alertChannel.setVibrationPattern(new long[]{0, 300, 100, 300, 100, 600});

        boolean sound = prefs.getBoolean(NotificationSettingsActivity.KEY_SOUND_ENABLED, true);
        if (sound) {
            AudioAttributes audioAttrs = new AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .build();
            alertChannel.setSound(getCurrentSoundUri(prefs), audioAttrs);
        } else {
            alertChannel.setSound(null, null);
        }

        nm.createNotificationChannel(alertChannel);
    }
}
