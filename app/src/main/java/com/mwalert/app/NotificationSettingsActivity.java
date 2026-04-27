package com.mwalert.app;

import android.app.Activity;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class NotificationSettingsActivity extends AppCompatActivity {

    public static final String KEY_SOUND_URI = "alert_sound_uri";
    public static final String KEY_VOLUME = "alert_volume";          // 0-100
    public static final String KEY_SOUND_ENABLED = "alert_sound_enabled";
    public static final String KEY_VIBRATE_ENABLED = "alert_vibrate_enabled";
    public static final String KEY_CHANNEL_VERSION = "alert_channel_version";

    private static final int REQ_PICK_SOUND = 1001;

    private SharedPreferences prefs;
    private TextView soundNameText, volumeLabel;
    private SeekBar volumeSlider;
    private Switch soundSwitch, vibrateSwitch;
    private Ringtone previewRingtone;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        buildUI();
    }

    private void buildUI() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 60, 40, 40);
        root.setBackgroundColor(0xFF0F0C29);
        scroll.addView(root);

        // Header
        TextView title = new TextView(this);
        title.setText("🔔  Alert Settings");
        title.setTextSize(28);
        title.setTextColor(0xFF00F2FE);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 20, 0, 30);
        root.addView(title);

        // === SOUND TOGGLE ===
        addSection(root, "SOUND");
        soundSwitch = new Switch(this);
        soundSwitch.setText("  Play sound on new job");
        soundSwitch.setTextColor(0xFFEEEEEE);
        soundSwitch.setTextSize(16);
        soundSwitch.setChecked(prefs.getBoolean(KEY_SOUND_ENABLED, true));
        soundSwitch.setPadding(0, 16, 0, 16);
        soundSwitch.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean(KEY_SOUND_ENABLED, on).apply();
            bumpChannelVersion();
        });
        root.addView(soundSwitch);

        // === RINGTONE PICKER ===
        addLabel(root, "ALERT TONE");
        soundNameText = new TextView(this);
        soundNameText.setTextColor(0xFFCCCCCC);
        soundNameText.setTextSize(14);
        soundNameText.setPadding(28, 22, 28, 22);
        soundNameText.setBackgroundColor(0xFF1A1A2E);
        updateSoundName();
        root.addView(soundNameText);

        Button pickBtn = new Button(this);
        pickBtn.setText("Choose Tone");
        pickBtn.setTextColor(0xFFFFFFFF);
        pickBtn.setBackgroundColor(0xFF667EEA);
        LinearLayout.LayoutParams pickP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 110);
        pickP.setMargins(0, 12, 0, 8);
        pickBtn.setLayoutParams(pickP);
        pickBtn.setOnClickListener(v -> openSoundPicker());
        root.addView(pickBtn);

        // === VOLUME SLIDER ===
        addLabel(root, "VOLUME");
        volumeLabel = new TextView(this);
        volumeLabel.setTextColor(0xFF888888);
        volumeLabel.setTextSize(13);
        volumeLabel.setPadding(0, 0, 0, 8);
        root.addView(volumeLabel);

        volumeSlider = new SeekBar(this);
        volumeSlider.setMax(100);
        volumeSlider.setProgress(prefs.getInt(KEY_VOLUME, 80));
        volumeSlider.setPadding(8, 16, 8, 16);
        volumeSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                volumeLabel.setText(progress + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {
                prefs.edit().putInt(KEY_VOLUME, seekBar.getProgress()).apply();
            }
        });
        root.addView(volumeSlider);
        volumeLabel.setText(volumeSlider.getProgress() + "%");

        // === VIBRATE TOGGLE ===
        addSection(root, "VIBRATION");
        vibrateSwitch = new Switch(this);
        vibrateSwitch.setText("  Vibrate on new job");
        vibrateSwitch.setTextColor(0xFFEEEEEE);
        vibrateSwitch.setTextSize(16);
        vibrateSwitch.setChecked(prefs.getBoolean(KEY_VIBRATE_ENABLED, true));
        vibrateSwitch.setPadding(0, 16, 0, 16);
        vibrateSwitch.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean(KEY_VIBRATE_ENABLED, on).apply();
            bumpChannelVersion();
        });
        root.addView(vibrateSwitch);

        // === TEST BUTTON ===
        Button testBtn = new Button(this);
        testBtn.setText("🔊  Test Tone");
        testBtn.setTextColor(0xFFFFFFFF);
        testBtn.setBackgroundColor(0xFF4ADE80);
        LinearLayout.LayoutParams testP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 130);
        testP.setMargins(0, 30, 0, 12);
        testBtn.setLayoutParams(testP);
        testBtn.setOnClickListener(v -> testSound());
        root.addView(testBtn);

        // === DONE BUTTON ===
        Button doneBtn = new Button(this);
        doneBtn.setText("Done");
        doneBtn.setTextColor(0xFFFFFFFF);
        doneBtn.setBackgroundColor(0xFF667EEA);
        LinearLayout.LayoutParams doneP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 130);
        doneP.setMargins(0, 16, 0, 30);
        doneBtn.setLayoutParams(doneP);
        doneBtn.setOnClickListener(v -> finish());
        root.addView(doneBtn);

        // Helper text
        TextView help = new TextView(this);
        help.setText("ℹ️  After changing sound or vibrate settings, the next alert uses your new choice.");
        help.setTextColor(0xFF888888);
        help.setTextSize(12);
        help.setPadding(8, 8, 8, 8);
        root.addView(help);

        setContentView(scroll);
    }

    private void addSection(LinearLayout parent, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFF667EEA);
        t.setTextSize(12);
        t.setLetterSpacing(0.2f);
        t.setPadding(0, 24, 0, 8);
        parent.addView(t);
    }

    private void addLabel(LinearLayout parent, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(0xFF888888);
        t.setTextSize(11);
        t.setLetterSpacing(0.15f);
        t.setPadding(0, 22, 0, 8);
        parent.addView(t);
    }

    private void updateSoundName() {
        Uri uri = getCurrentSoundUri();
        try {
            Ringtone r = RingtoneManager.getRingtone(this, uri);
            String title = r != null ? r.getTitle(this) : "Default";
            soundNameText.setText("♪ " + title);
        } catch (Exception e) {
            soundNameText.setText("♪ Default notification sound");
        }
    }

    private Uri getCurrentSoundUri() {
        String saved = prefs.getString(KEY_SOUND_URI, null);
        if (saved != null && !saved.isEmpty()) {
            try { return Uri.parse(saved); } catch (Exception ignored) {}
        }
        return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
    }

    private void openSoundPicker() {
        Intent intent = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Choose Alert Tone");
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false);
        intent.putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, getCurrentSoundUri());
        try {
            startActivityForResult(intent, REQ_PICK_SOUND);
        } catch (Exception e) {
            Toast.makeText(this, "Sound picker not available on this device",
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_SOUND && resultCode == RESULT_OK && data != null) {
            Uri picked = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
            if (picked != null) {
                prefs.edit().putString(KEY_SOUND_URI, picked.toString()).apply();
                updateSoundName();
                bumpChannelVersion();
            }
        }
    }

    private void testSound() {
        // Stop any playing preview
        if (previewRingtone != null) {
            try { previewRingtone.stop(); } catch (Exception ignored) {}
        }

        // Build the sound URI
        Uri soundUri = getCurrentSoundUri();
        try {
            previewRingtone = RingtoneManager.getRingtone(this, soundUri);
            if (previewRingtone == null) {
                Toast.makeText(this, "Could not load sound", Toast.LENGTH_SHORT).show();
                return;
            }

            // Set volume hint via AudioAttributes (works on API 28+)
            int volume = volumeSlider.getProgress();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    previewRingtone.setVolume(volume / 100f);
                } catch (Exception ignored) {}
            } else {
                // For older Android, temporarily adjust system notification volume
                try {
                    AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
                    int max = am.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION);
                    int level = Math.round(max * (volume / 100f));
                    am.setStreamVolume(AudioManager.STREAM_NOTIFICATION, level, 0);
                } catch (Exception ignored) {}
            }

            previewRingtone.play();

            // Trigger vibration if enabled
            if (vibrateSwitch.isChecked()) {
                try {
                    android.os.Vibrator v = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
                    if (v != null && v.hasVibrator()) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            v.vibrate(android.os.VibrationEffect.createOneShot(400, android.os.VibrationEffect.DEFAULT_AMPLITUDE));
                        } else {
                            v.vibrate(400);
                        }
                    }
                } catch (Exception ignored) {}
            }

            Toast.makeText(this, "Playing test...", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Test failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Increment channel version so PollingService creates a fresh channel
     * with the new sound/vibrate settings (Android requires this — channel
     * settings are immutable once created).
     */
    private void bumpChannelVersion() {
        int v = prefs.getInt(KEY_CHANNEL_VERSION, 0) + 1;
        prefs.edit().putInt(KEY_CHANNEL_VERSION, v).apply();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (previewRingtone != null) {
            try { previewRingtone.stop(); } catch (Exception ignored) {}
            previewRingtone = null;
        }
    }
}
