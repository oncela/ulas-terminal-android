package com.ulasgrup.terminal;

import android.app.Application;
import android.content.Context;
import android.os.Build;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Çökme kaydı: uygulama çökerse hata ayrıntısı dosyaya yazılır, sonraki açılışta sunucuya gönderilir
 * (Cihazlar → storage/logs/uygulama-hata.log). Çöken açılıştan sonra okuyucu başlatılmaz (güvenli açılış).
 */
public class Uygulama extends Application {
    static final String HATA_DOSYASI = "son_hata.txt";
    private static final String RAPOR = "https://sistem.ulasgrup.com.tr/uygulama/hata";

    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler eski = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                try (FileOutputStream o = new FileOutputStream(new File(getFilesDir(), HATA_DOSYASI))) {
                    o.write(("Thread: " + t.getName() + "\n" + sw).getBytes(StandardCharsets.UTF_8));
                }
            } catch (Throwable ignored) { }
            if (eski != null) eski.uncaughtException(t, e);
        });
    }

    /** Önceki çökmenin kaydı varsa gönderir; varsa true (bu açılış güvenli modda olmalı) */
    static boolean oncekiHatayiGonder(Context c) {
        final File f = new File(c.getFilesDir(), HATA_DOSYASI);
        if (!f.exists()) return false;
        final String metin;
        try {
            byte[] b = new byte[(int) Math.min(f.length(), 18000)];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) { int n = in.read(b); metin = new String(b, 0, Math.max(n, 0), StandardCharsets.UTF_8); }
        } catch (Throwable e) { f.delete(); return true; }
        f.delete();
        new Thread(() -> {
            try {
                JSONObject o = new JSONObject();
                o.put("surum", BuildConfig.VERSION_NAME);
                o.put("model", Build.MODEL);
                o.put("android", Build.VERSION.RELEASE);
                o.put("hata", metin);
                HttpURLConnection h = (HttpURLConnection) new URL(RAPOR).openConnection();
                h.setRequestMethod("POST");
                h.setConnectTimeout(10000);
                h.setReadTimeout(10000);
                h.setDoOutput(true);
                h.setRequestProperty("Content-Type", "application/json");
                h.setRequestProperty("Accept", "application/json");
                try (OutputStream os = h.getOutputStream()) { os.write(o.toString().getBytes(StandardCharsets.UTF_8)); }
                h.getResponseCode();
                h.disconnect();
            } catch (Throwable ignored) { }
        }).start();
        return true;
    }
}
