package com.mwalert.app;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class ApiHelper {

    private static final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build();

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    public static String buildApiBase(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        // strip protocol if present
        if (s.toLowerCase().startsWith("http://")) s = s.substring(7);
        if (s.toLowerCase().startsWith("https://")) s = s.substring(8);
        // strip trailing slash
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);

        // ngrok always uses HTTPS on 443 — strip any port
        if (s.contains("ngrok")) {
            int colon = s.indexOf(':');
            if (colon > 0) s = s.substring(0, colon);
            return "https://" + s;
        }
        return "http://" + s;
    }

    public static String get(String url, String token) throws IOException {
        Request.Builder rb = new Request.Builder()
                .url(url)
                .addHeader("ngrok-skip-browser-warning", "true");
        if (token != null && !token.isEmpty()) {
            rb.addHeader("Authorization", "Bearer " + token);
        }
        try (Response resp = client.newCall(rb.build()).execute()) {
            if (resp.body() == null) return "{}";
            return resp.body().string();
        }
    }

    /** Plain GET with no auth headers (used for fetching Netlify config.json) */
    public static String getRaw(String url) throws IOException {
        Request req = new Request.Builder().url(url).build();
        try (Response resp = client.newCall(req).execute()) {
            if (resp.body() == null) return "{}";
            return resp.body().string();
        }
    }

    public static String post(String url, String body, String token) throws IOException {
        RequestBody rb = RequestBody.create(body, JSON);
        Request.Builder reqBuilder = new Request.Builder()
                .url(url)
                .post(rb)
                .addHeader("ngrok-skip-browser-warning", "true");
        if (token != null && !token.isEmpty()) {
            reqBuilder.addHeader("Authorization", "Bearer " + token);
        }
        try (Response resp = client.newCall(reqBuilder.build()).execute()) {
            if (resp.body() == null) return "{}";
            return resp.body().string();
        }
    }
}
