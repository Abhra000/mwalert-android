package com.mwalert.app;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {

    public static final String PREFS = "mwalert_prefs";
    public static final String KEY_TOKEN = "token";
    public static final String KEY_SERVER = "server";
    public static final String KEY_EMAIL = "email";
    public static final String KEY_LAST_SEEN_ID = "last_seen_job_id";

    // === HARDCODED CONFIG SOURCE ===
    // The APK fetches the current ngrok URL from this Netlify URL's config.json.
    // Update CONFIG_URL below to YOUR Netlify domain.
    private static final String CONFIG_URL = "https://mw-alert.netlify.app/config.json";

    private SharedPreferences prefs;
    private LinearLayout loginLayout;
    private LinearLayout dashboardLayout;
    private EditText emailInput, passwordInput;
    private TextView statusText, jobsText, msgText, serverInfoText;
    private Button loginBtn, logoutBtn, batteryBtn;

    private static final int REQ_NOTIFICATION = 1001;

    private final android.os.Handler refreshHandler = new android.os.Handler();
    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            loadJobs();
            refreshHandler.postDelayed(this, 5000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        buildUI();

        // Auto-login if token saved
        if (prefs.getString(KEY_TOKEN, "").length() > 0) {
            showDashboard();
        } else {
            showLogin();
            // Pre-fetch server URL in background
            fetchServerUrl(null);
        }

        requestNotifPermission();
    }

    private void requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIFICATION);
            }
        }
    }

    private void buildUI() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(40, 60, 40, 40);
        root.setBackgroundColor(0xFF0F0C29);
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("MW Alert");
        title.setTextSize(36);
        title.setTextColor(0xFF00F2FE);
        title.setGravity(android.view.Gravity.CENTER);
        title.setPadding(0, 20, 0, 4);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("MICROWORKERS MONITOR");
        subtitle.setTextSize(11);
        subtitle.setTextColor(0xFF888888);
        subtitle.setLetterSpacing(0.2f);
        subtitle.setGravity(android.view.Gravity.CENTER);
        subtitle.setPadding(0, 0, 0, 30);
        root.addView(subtitle);

        // === LOGIN LAYOUT (Email + Password ONLY) ===
        loginLayout = new LinearLayout(this);
        loginLayout.setOrientation(LinearLayout.VERTICAL);

        addLabel(loginLayout, "EMAIL");
        emailInput = addInput(loginLayout, "you@example.com",
                prefs.getString(KEY_EMAIL, ""));

        addLabel(loginLayout, "PASSWORD");
        passwordInput = addInput(loginLayout, "••••••••", "");
        passwordInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);

        loginBtn = new Button(this);
        loginBtn.setText("Sign In");
        loginBtn.setTextColor(0xFFFFFFFF);
        loginBtn.setBackgroundColor(0xFF667EEA);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 130);
        lp.setMargins(0, 30, 0, 10);
        loginBtn.setLayoutParams(lp);
        loginBtn.setOnClickListener(v -> doLogin());
        loginLayout.addView(loginBtn);

        msgText = new TextView(this);
        msgText.setTextColor(0xFFFF6B6B);
        msgText.setPadding(0, 16, 0, 0);
        msgText.setGravity(android.view.Gravity.CENTER);
        loginLayout.addView(msgText);

        serverInfoText = new TextView(this);
        serverInfoText.setText("");
        serverInfoText.setTextColor(0xFF555555);
        serverInfoText.setTextSize(11);
        serverInfoText.setPadding(0, 12, 0, 0);
        serverInfoText.setGravity(android.view.Gravity.CENTER);
        loginLayout.addView(serverInfoText);

        root.addView(loginLayout);

        // === DASHBOARD LAYOUT ===
        dashboardLayout = new LinearLayout(this);
        dashboardLayout.setOrientation(LinearLayout.VERTICAL);
        dashboardLayout.setVisibility(View.GONE);

        statusText = new TextView(this);
        statusText.setText("● Connected — background polling active");
        statusText.setTextColor(0xFF4ADE80);
        statusText.setTextSize(13);
        statusText.setPadding(20, 16, 20, 16);
        statusText.setBackgroundColor(0x1A4ADE80);
        dashboardLayout.addView(statusText);

        // === BELL ICON / NOTIFICATION SETTINGS BUTTON ===
        Button bellBtn = new Button(this);
        bellBtn.setText("🔔  Notification Settings");
        bellBtn.setTextColor(0xFFFFFFFF);
        bellBtn.setBackgroundColor(0xFF302B63);
        bellBtn.setTextSize(15);
        LinearLayout.LayoutParams bellP = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 130);
        bellP.setMargins(0, 14, 0, 0);
        bellBtn.setLayoutParams(bellP);
        bellBtn.setOnClickListener(v -> {
            Intent settingsIntent = new Intent(this, NotificationSettingsActivity.class);
            startActivity(settingsIntent);
        });
        dashboardLayout.addView(bellBtn);

        TextView jobsHeader = new TextView(this);
        jobsHeader.setText("\nRECENT MATCHED JOBS\n");
        jobsHeader.setTextSize(13);
        jobsHeader.setTextColor(0xFFAAAAAA);
        jobsHeader.setLetterSpacing(0.1f);
        dashboardLayout.addView(jobsHeader);

        jobsText = new TextView(this);
        jobsText.setText("Waiting for jobs...");
        jobsText.setTextColor(0xFFCCCCCC);
        jobsText.setTextSize(13);
        jobsText.setLineSpacing(6f, 1f);
        dashboardLayout.addView(jobsText);

        batteryBtn = new Button(this);
        batteryBtn.setText("Disable Battery Optimization");
        batteryBtn.setTextColor(0xFFFFFFFF);
        batteryBtn.setBackgroundColor(0xFFFF9500);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 120);
        bp.setMargins(0, 30, 0, 10);
        batteryBtn.setLayoutParams(bp);
        batteryBtn.setOnClickListener(v -> requestBatteryOptOff());
        dashboardLayout.addView(batteryBtn);

        Button refreshUrlBtn = new Button(this);
        refreshUrlBtn.setText("Refresh Server URL");
        refreshUrlBtn.setTextColor(0xFFFFFFFF);
        refreshUrlBtn.setBackgroundColor(0xFF667EEA);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 110);
        rp.setMargins(0, 14, 0, 10);
        refreshUrlBtn.setLayoutParams(rp);
        refreshUrlBtn.setOnClickListener(v -> {
            Toast.makeText(this, "Fetching latest server URL...", Toast.LENGTH_SHORT).show();
            fetchServerUrl(server -> {
                if (server != null) {
                    Toast.makeText(this, "Updated: " + server, Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "Could not fetch URL", Toast.LENGTH_LONG).show();
                }
            });
        });
        dashboardLayout.addView(refreshUrlBtn);

        logoutBtn = new Button(this);
        logoutBtn.setText("Logout");
        logoutBtn.setTextColor(0xFFFF6B6B);
        logoutBtn.setBackgroundColor(0x33FF6B6B);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 110);
        lp2.setMargins(0, 14, 0, 30);
        logoutBtn.setLayoutParams(lp2);
        logoutBtn.setOnClickListener(v -> logout());
        dashboardLayout.addView(logoutBtn);

        root.addView(dashboardLayout);

        setContentView(scroll);
    }

    private void addLabel(LinearLayout parent, String text) {
        TextView lbl = new TextView(this);
        lbl.setText(text);
        lbl.setTextColor(0xFF888888);
        lbl.setTextSize(11);
        lbl.setLetterSpacing(0.15f);
        lbl.setPadding(0, 18, 0, 6);
        parent.addView(lbl);
    }

    private EditText addInput(LinearLayout parent, String hint, String defaultValue) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(defaultValue);
        e.setTextColor(0xFFFFFFFF);
        e.setHintTextColor(0xFF555555);
        e.setBackgroundColor(0xFF1A1A2E);
        e.setPadding(28, 28, 28, 28);
        e.setSingleLine(true);
        parent.addView(e);
        return e;
    }

    private void showLogin() {
        loginLayout.setVisibility(View.VISIBLE);
        dashboardLayout.setVisibility(View.GONE);
        msgText.setText("");
    }

    private void showDashboard() {
        loginLayout.setVisibility(View.GONE);
        dashboardLayout.setVisibility(View.VISIBLE);
        startPollingService();
        refreshHandler.removeCallbacks(refreshRunnable);
        refreshHandler.post(refreshRunnable);
    }

    /**
     * Fetch current ngrok URL from CONFIG_URL (Netlify config.json),
     * save it to prefs. Optionally invoke a callback when done.
     */
    interface ServerUrlCallback {
        void onResult(String server);
    }

    private void fetchServerUrl(ServerUrlCallback cb) {
        new Thread(() -> {
            try {
                String resp = ApiHelper.getRaw(CONFIG_URL);
                JSONObject json = new JSONObject(resp);
                String server = json.optString("server", "").trim();
                if (server.length() > 0) {
                    prefs.edit().putString(KEY_SERVER, server).apply();
                    runOnUiThread(() -> {
                        if (serverInfoText != null) {
                            serverInfoText.setText("Server: " + server);
                        }
                        if (cb != null) cb.onResult(server);
                    });
                } else {
                    runOnUiThread(() -> { if (cb != null) cb.onResult(null); });
                }
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (serverInfoText != null) {
                        serverInfoText.setText("⚠ Could not fetch server URL");
                    }
                    if (cb != null) cb.onResult(null);
                });
            }
        }).start();
    }

    private void doLogin() {
        String email = emailInput.getText().toString().trim();
        String password = passwordInput.getText().toString();

        if (email.isEmpty() || password.isEmpty()) {
            msgText.setText("Email and password required");
            return;
        }

        msgText.setText("Connecting...");
        msgText.setTextColor(0xFFAAAAAA);
        loginBtn.setEnabled(false);

        // Always fetch the latest server URL first, then login
        new Thread(() -> {
            String server = "";
            try {
                String resp = ApiHelper.getRaw(CONFIG_URL);
                JSONObject json = new JSONObject(resp);
                server = json.optString("server", "").trim();
            } catch (Exception ignored) {}

            if (server.isEmpty()) {
                // Fallback to last-saved server
                server = prefs.getString(KEY_SERVER, "");
            }

            if (server.isEmpty()) {
                runOnUiThread(() -> {
                    msgText.setText("Server unreachable. Try later.");
                    msgText.setTextColor(0xFFFF6B6B);
                    loginBtn.setEnabled(true);
                });
                return;
            }

            prefs.edit().putString(KEY_SERVER, server).apply();
            String apiBase = ApiHelper.buildApiBase(server);

            try {
                String body = new JSONObject()
                        .put("email", email)
                        .put("password", password)
                        .toString();
                String resp = ApiHelper.post(apiBase + "/api/login", body, null);

                // If we get HTML back, it's likely the ngrok warning page
                if (resp.trim().startsWith("<")) {
                    runOnUiThread(() -> {
                        msgText.setText("Server returned HTML — visit ngrok URL once in browser to bypass warning");
                        msgText.setTextColor(0xFFFF6B6B);
                        loginBtn.setEnabled(true);
                    });
                    return;
                }

                JSONObject json = new JSONObject(resp);

                if (json.has("token")) {
                    String token = json.getString("token");
                    prefs.edit()
                            .putString(KEY_TOKEN, token)
                            .putString(KEY_EMAIL, email)
                            .apply();
                    runOnUiThread(() -> {
                        msgText.setText("✓ Welcome!");
                        msgText.setTextColor(0xFF4ADE80);
                        loginBtn.setEnabled(true);
                        showDashboard();
                    });
                } else {
                    String err = json.optString("error", "Login failed");
                    runOnUiThread(() -> {
                        msgText.setText(err);
                        msgText.setTextColor(0xFFFF6B6B);
                        loginBtn.setEnabled(true);
                    });
                }
            } catch (Exception ex) {
                runOnUiThread(() -> {
                    msgText.setText("Connection failed: " + ex.getMessage());
                    msgText.setTextColor(0xFFFF6B6B);
                    loginBtn.setEnabled(true);
                });
            }
        }).start();
    }

    private void loadJobs() {
        String token = prefs.getString(KEY_TOKEN, "");
        String server = prefs.getString(KEY_SERVER, "");
        if (token.isEmpty() || server.isEmpty()) return;

        String apiBase = ApiHelper.buildApiBase(server);

        new Thread(() -> {
            try {
                String resp = ApiHelper.get(apiBase + "/api/jobs", token);
                JSONObject json = new JSONObject(resp);
                JSONArray jobs = json.optJSONArray("jobs");
                if (jobs == null) return;

                StringBuilder sb = new StringBuilder();
                if (jobs.length() == 0) {
                    sb.append("No matching jobs yet — monitoring...");
                } else {
                    int max = Math.min(jobs.length(), 30);
                    for (int i = 0; i < max; i++) {
                        JSONObject j = jobs.getJSONObject(i);
                        sb.append("$").append(j.optString("payment", "?"))
                                .append(" — ").append(j.optString("title", ""))
                                .append("\n").append(j.optString("found_at", ""))
                                .append("\n\n");
                    }
                }
                String text = sb.toString();
                runOnUiThread(() -> {
                    jobsText.setText(text);
                    statusText.setText("● Connected • " + jobs.length() + " jobs • bg polling");
                    statusText.setTextColor(0xFF4ADE80);
                });
            } catch (Exception ex) {
                runOnUiThread(() -> {
                    statusText.setText("● Disconnected — server may be down");
                    statusText.setTextColor(0xFFFF6B6B);
                });
            }
        }).start();
    }

    private void startPollingService() {
        Intent serviceIntent = new Intent(this, PollingService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void requestBatteryOptOff() {
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Open Settings → Apps → MW Alert → Battery → Unrestricted",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void logout() {
        prefs.edit()
                .remove(KEY_TOKEN)
                .remove(KEY_LAST_SEEN_ID)
                .apply();
        stopService(new Intent(this, PollingService.class));
        refreshHandler.removeCallbacks(refreshRunnable);
        showLogin();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (dashboardLayout != null && dashboardLayout.getVisibility() == View.VISIBLE) {
            refreshHandler.post(refreshRunnable);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        refreshHandler.removeCallbacks(refreshRunnable);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }
}
